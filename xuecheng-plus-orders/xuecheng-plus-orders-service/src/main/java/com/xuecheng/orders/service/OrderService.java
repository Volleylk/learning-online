package com.xuecheng.orders.service;

import com.xuecheng.messagesdk.model.po.MqMessage;
import com.xuecheng.orders.model.dto.AddOrderDto;
import com.xuecheng.orders.model.dto.PayRecordDto;
import com.xuecheng.orders.model.dto.PayStatusDto;
import com.xuecheng.orders.model.po.XcPayRecord;

/**
 * 订单服务
 */
public interface OrderService {

    /**
     * 创建商品订单（含支付记录和二维码）
     */
    PayRecordDto createOrder(String userId, AddOrderDto addOrderDto);

    /**
     * 根据支付流水号查询支付记录
     */
    XcPayRecord getPayRecordByPayno(String payNo);

    /**
     * 请求支付宝查询支付结果
     */
    PayRecordDto queryPayResult(String payNo);

    /**
     * 保存支付宝支付结果
     */
    void saveAliPayStatus(PayStatusDto payStatusDto);

    void notifyPayResult(MqMessage message);
}
