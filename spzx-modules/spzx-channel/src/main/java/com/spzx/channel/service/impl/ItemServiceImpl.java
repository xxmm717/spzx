package com.spzx.channel.service.impl;

import com.alibaba.fastjson2.JSON;
import com.spzx.channel.domain.ItemVo;
import com.spzx.channel.service.IItemService;
import com.spzx.common.core.constant.SecurityConstants;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.exception.ServiceException;
import com.spzx.product.api.RemoteProductService;
import com.spzx.product.api.domain.Product;
import com.spzx.product.api.domain.ProductDetails;
import com.spzx.product.api.domain.ProductSku;
import com.spzx.product.api.domain.vo.SkuPrice;
import com.spzx.product.api.domain.vo.SkuStockVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

@Service
@Slf4j
public class ItemServiceImpl implements IItemService {

    @Autowired
    private RemoteProductService remoteProductService;
    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;
    @Autowired
    private ThreadPoolExecutor threadPoolExecutor;

    @Override
    public ItemVo item(Long skuId) throws Exception {

        //远程调用商品微服务接口之前 提前知道用户访问商品SKUID是否存在于bitmap中
        String key = "sku:product:data";
        Boolean flag = redisTemplate.opsForValue().getBit(key, skuId);

        if (!flag) {
            log.error("用户查询商品sku不存在：{}", skuId);
            //查询数据不存在直接返回空对象
            throw new ServiceException("用户查询商品sku不存在");
        }
        ItemVo itemVo = new ItemVo();
        /*
        * 每个方法最后都传了 threadPoolExecutor，意思是这些任务跑在 [hreadPoolConfig.java 自定义的线程池里，而不是 JDK 默认的 ForkJoinPool.commonPool()。
        */
        //获取sku信息
        //任务有返回值。任务 1 查完 ProductSku 后把 ProductSku 返回，后面三个任务才能拿到 productId 继续查
        CompletableFuture<ProductSku> skuCompletableFuture = CompletableFuture.supplyAsync(() -> {
            R<ProductSku> productSkuResult = remoteProductService.getProductSku(skuId, SecurityConstants.INNER);
            if (R.FAIL == productSkuResult.getCode()) {
                throw new ServiceException(productSkuResult.getMsg());
            }
            ProductSku productSku = productSkuResult.getData();
            itemVo.setProductSku(productSku);
            return productSku;
        }, threadPoolExecutor);

        //获取商品信息
        //依赖 productSku.getProductId()，所以都挂在任务 1 后面
        CompletableFuture<Void> productComCompletableFuture = skuCompletableFuture.thenAcceptAsync(productSku -> {
            R<Product> productResult = remoteProductService.getProduct(productSku.getProductId(), SecurityConstants.INNER);
            if (R.FAIL == productResult.getCode()) {
                throw new ServiceException(productResult.getMsg());
            }
            Product product = productResult.getData();
            itemVo.setProduct(product);
            itemVo.setSliderUrlList(Arrays.asList(product.getSliderUrls().split(",")));
            itemVo.setSpecValueList(JSON.parseArray(product.getSpecValue()));
        }, threadPoolExecutor);

        //获取商品最新价格
        //并行
        CompletableFuture<Void> skuPriceCompletableFuture = CompletableFuture.runAsync(() -> {
            R<SkuPrice> skuPriceResult = remoteProductService.getSkuPrice(skuId, SecurityConstants.INNER);
            if (R.FAIL == skuPriceResult.getCode()) {
                throw new ServiceException(skuPriceResult.getMsg());
            }
            SkuPrice skuPrice = skuPriceResult.getData();
            itemVo.setSkuPrice(skuPrice);
        }, threadPoolExecutor);

        //获取商品详情
        //依赖 productSku.getProductId()，所以都挂在任务 1 后面
        CompletableFuture<Void> productDetailsComCompletableFuture = skuCompletableFuture.thenAcceptAsync(productSku -> {
            R<ProductDetails> productDetailsResult = remoteProductService.getProductDetails(productSku.getProductId(), SecurityConstants.INNER);
            if (R.FAIL == productDetailsResult.getCode()) {
                throw new ServiceException(productDetailsResult.getMsg());
            }
            ProductDetails productDetails = productDetailsResult.getData();
            itemVo.setDetailsImageUrlList(Arrays.asList(productDetails.getImageUrls().split(",")));
        }, threadPoolExecutor);

        //获取商品规格对应商品skuId信息
        //依赖 productSku.getProductId()，所以都挂在任务 1 后面
        CompletableFuture<Void> skuSpecValueComCompletableFuture = skuCompletableFuture.thenAcceptAsync(productSku -> {
            R<Map<String, Long>> skuSpecValueResult = remoteProductService.getSkuSpecValue(productSku.getProductId(), SecurityConstants.INNER);
            if (R.FAIL == skuSpecValueResult.getCode()) {
                throw new ServiceException(skuSpecValueResult.getMsg());
            }
            Map<String, Long> skuSpecValueMap = skuSpecValueResult.getData();
            itemVo.setSkuSpecValueMap(skuSpecValueMap);
        }, threadPoolExecutor);

        //获取商品库存信息
        // 并行
        CompletableFuture<Void> skuStockVoComCompletableFuture = CompletableFuture.runAsync(() -> {
            R<SkuStockVo> skuStockResult = remoteProductService.getSkuStock(skuId, SecurityConstants.INNER);
            if (R.FAIL == skuStockResult.getCode()) {
                throw new ServiceException(skuStockResult.getMsg());
            }
            SkuStockVo skuStockVo = skuStockResult.getData();
            itemVo.setSkuStockVo(skuStockVo);
        }, threadPoolExecutor);

        //x.组合以上七个异步任务
        CompletableFuture.allOf(
                skuCompletableFuture,
                productComCompletableFuture,
                skuPriceCompletableFuture,
                productDetailsComCompletableFuture,
                skuSpecValueComCompletableFuture,
                skuStockVoComCompletableFuture
        ).join();
        return itemVo;
    }

}
