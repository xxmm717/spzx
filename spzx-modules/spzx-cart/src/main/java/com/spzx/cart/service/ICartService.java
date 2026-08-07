package com.spzx.cart.service;

import com.spzx.cart.api.domain.CartInfo;

import java.util.List;

public interface ICartService {
    void addToCart(Long skuId, Integer skuNum);
    List<CartInfo> getCartList();

}
