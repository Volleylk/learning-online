package com.xuecheng.orders.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xuecheng.base.exception.XueChengPlusException;
import com.xuecheng.messagesdk.model.po.MqMessage;
import com.xuecheng.messagesdk.service.MqMessageService;
import com.xuecheng.orders.config.AlipayConfig;
import com.xuecheng.orders.config.PayNotifyConfig;
import com.xuecheng.orders.mapper.XcOrdersGoodsMapper;
import com.xuecheng.orders.mapper.XcOrdersMapper;
import com.xuecheng.orders.mapper.XcPayRecordMapper;
import com.xuecheng.orders.model.dto.AddOrderDto;
import com.xuecheng.orders.model.dto.PayRecordDto;
import com.xuecheng.orders.model.dto.PayStatusDto;
import com.xuecheng.orders.model.po.XcOrders;
import com.xuecheng.orders.model.po.XcOrdersGoods;
import com.xuecheng.orders.model.po.XcPayRecord;
import com.xuecheng.orders.service.OrderService;
import com.xuecheng.orders.util.IdWorkerUtils;
import com.xuecheng.orders.util.QRCodeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Map;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    XcOrdersMapper ordersMapper;

    @Autowired
    XcOrdersGoodsMapper ordersGoodsMapper;

    @Autowired
    XcPayRecordMapper payRecordMapper;

    @Autowired
    MqMessageService mqMessageService;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Value("${pay.qrcodeurl}")
    String qrcodeurl;

    @Value("${pay.alipay.APP_ID}")
    String APP_ID;

    @Value("${pay.alipay.APP_PRIVATE_KEY}")
    String APP_PRIVATE_KEY;

    @Value("${pay.alipay.ALIPAY_PUBLIC_KEY}")
    String ALIPAY_PUBLIC_KEY;

    @Transactional
    @Override
    public PayRecordDto createOrder(String userId, AddOrderDto addOrderDto) {
        XcOrders orders = saveXcOrders(userId, addOrderDto);
        if (orders == null) {
            XueChengPlusException.cast("订单创建失败");
        }
        XcPayRecord payRecord = createPayRecord(orders);
        String qrcode;
        try {
            String url = String.format(qrcodeurl, payRecord.getPayNo());
            qrcode = QRCodeUtil.createQRCode(url, 200, 200);
        } catch (Exception e) {
            log.error("生成二维码异常", e);
            XueChengPlusException.cast("生成二维码出错");
            return null;
        }
        PayRecordDto dto = new PayRecordDto();
        BeanUtils.copyProperties(payRecord, dto);
        dto.setQrcode(qrcode);
        return dto;
    }

    @Override
    public XcPayRecord getPayRecordByPayno(String payNo) {
        return payRecordMapper.selectOne(new LambdaQueryWrapper<XcPayRecord>().eq(XcPayRecord::getPayNo, payNo));
    }

    @Override
    public PayRecordDto queryPayResult(String payNo) {
        XcPayRecord payRecord = getPayRecordByPayno(payNo);
        if (payRecord == null) {
            XueChengPlusException.cast("请重新点击支付获取二维码");
        }
        if ("601002".equals(payRecord.getStatus())) {
            PayRecordDto dto = new PayRecordDto();
            BeanUtils.copyProperties(payRecord, dto);
            return dto;
        }
        PayStatusDto payStatusDto = queryPayResultFromAlipay(payNo);
        saveAliPayStatus(payStatusDto);
        payRecord = getPayRecordByPayno(payNo);
        PayRecordDto dto = new PayRecordDto();
        BeanUtils.copyProperties(payRecord, dto);
        return dto;
    }

    @Transactional
    @Override
    public void saveAliPayStatus(PayStatusDto payStatusDto) {
        String payNo = payStatusDto.getOut_trade_no();
        XcPayRecord payRecord = getPayRecordByPayno(payNo);
        if (payRecord == null) {
            XueChengPlusException.cast("支付记录找不到");
        }
        String tradeStatus = payStatusDto.getTrade_status();
        if (!"TRADE_SUCCESS".equals(tradeStatus)) {
            return;
        }
        Float totalPriceFen = payRecord.getTotalPrice() * 100;
        Float totalAmountFen = Float.parseFloat(payStatusDto.getTotal_amount()) * 100;
        if (totalPriceFen.intValue() != totalAmountFen.intValue()) {
            log.info("校验支付结果失败,支付记录:{},金额:{},支付宝:{},分", payRecord, totalPriceFen, totalAmountFen);
            XueChengPlusException.cast("校验支付结果失败");
        }
        XcPayRecord update = new XcPayRecord();
        update.setStatus("601002");
        update.setOutPayChannel("Alipay");
        update.setOutPayNo(payStatusDto.getTrade_no());
        update.setPaySuccessTime(LocalDateTime.now());
        payRecordMapper.update(update, new LambdaQueryWrapper<XcPayRecord>().eq(XcPayRecord::getPayNo, payNo));

        Long orderId = payRecord.getOrderId();
        XcOrders orders = ordersMapper.selectById(orderId);
        if (orders == null) {
            XueChengPlusException.cast("根据支付记录找不到订单");
        }
        XcOrders orderUpdate = new XcOrders();
        orderUpdate.setStatus("600002");
        ordersMapper.update(orderUpdate, new LambdaQueryWrapper<XcOrders>().eq(XcOrders::getId, orderId));

        // 保存消息记录，发 MQ 通知
        MqMessage mqMessage = mqMessageService.addMessage(
                PayNotifyConfig.MESSAGE_TYPE,
                orders.getOutBusinessId(), orders.getOrderType(), null);
        notifyPayResult(mqMessage);
    }

    @Override
    public void notifyPayResult(MqMessage message) {
        String msg = JSON.toJSONString(message);
        Message msgObj = MessageBuilder.withBody(msg.getBytes(StandardCharsets.UTF_8))
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        CorrelationData correlationData = new CorrelationData(message.getId().toString());
        correlationData.getFuture().addCallback(
                result -> {
                    if (result.isAck()) {
                        log.debug("通知支付结果消息发送成功, ID:{}", correlationData.getId());
                        mqMessageService.completed(message.getId());
                    } else {
                        log.error("通知支付结果消息发送失败, ID:{}, 原因{}", correlationData.getId(), result.getReason());
                    }
                },
                ex -> log.error("消息发送异常, ID:{}, 原因{}", correlationData.getId(), ex.getMessage()));
        rabbitTemplate.convertAndSend(PayNotifyConfig.PAYNOTIFY_EXCHANGE_FANOUT, "", msgObj, correlationData);
    }

    /**
     * 保存商品订单
     */
    private XcOrders saveXcOrders(String userId, AddOrderDto addOrderDto) {
        // 幂等：按 outBusinessId 查是否已有订单
        XcOrders existing = ordersMapper.selectOne(
                new LambdaQueryWrapper<XcOrders>().eq(XcOrders::getOutBusinessId, addOrderDto.getOutBusinessId()));
        if (existing != null) {
            return existing;
        }
        XcOrders orders = new XcOrders();
        long orderId = IdWorkerUtils.getInstance().nextId();
        orders.setId(orderId);
        orders.setTotalPrice(addOrderDto.getTotalPrice());
        orders.setCreateDate(LocalDateTime.now());
        orders.setStatus("600001");//未支付
        orders.setUserId(userId);
        orders.setOrderType(addOrderDto.getOrderType());
        orders.setOrderName(addOrderDto.getOrderName());
        orders.setOrderDescrip(addOrderDto.getOrderDescrip());
        orders.setOrderDetail(addOrderDto.getOrderDetail());
        orders.setOutBusinessId(addOrderDto.getOutBusinessId());
        ordersMapper.insert(orders);

        // 订单明细
        List<XcOrdersGoods> goodsList = JSON.parseArray(addOrderDto.getOrderDetail(), XcOrdersGoods.class);
        if (goodsList != null) {
            for (XcOrdersGoods g : goodsList) {
                g.setOrderId(orderId);
                ordersGoodsMapper.insert(g);
            }
        }
        return orders;
    }

    /**
     * 创建支付记录
     */
    public XcPayRecord createPayRecord(XcOrders orders) {
        if (orders == null) {
            XueChengPlusException.cast("订单不存在");
        }
        if ("600002".equals(orders.getStatus())) {
            XueChengPlusException.cast("订单已支付");
        }
        XcPayRecord record = new XcPayRecord();
        long payNo = IdWorkerUtils.getInstance().nextId();
        record.setPayNo(payNo);
        record.setOrderId(orders.getId());
        record.setOrderName(orders.getOrderName());
        record.setTotalPrice(orders.getTotalPrice());
        record.setCurrency("CNY");
        record.setCreateDate(LocalDateTime.now());
        record.setStatus("601001");//未支付
        record.setUserId(orders.getUserId());
        payRecordMapper.insert(record);
        return record;
    }

    /**
     * 请求支付宝查询支付结果
     */
    public PayStatusDto queryPayResultFromAlipay(String payNo) {
        AlipayClient client = new DefaultAlipayClient(AlipayConfig.URL, APP_ID, APP_PRIVATE_KEY,
                "json", AlipayConfig.CHARSET, ALIPAY_PUBLIC_KEY, AlipayConfig.SIGNTYPE);
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no", payNo);
        request.setBizContent(bizContent.toString());
        AlipayTradeQueryResponse response;
        try {
            response = client.execute(request);
            if (!response.isSuccess()) {
                XueChengPlusException.cast("请求支付查询失败");
            }
        } catch (AlipayApiException e) {
            log.error("请求支付宝查询支付结果异常:{}", e.toString());
            XueChengPlusException.cast("请求支付查询失败");
            return null;
        }
        String resultJson = response.getBody();
        Map resultMap = JSON.parseObject(resultJson, Map.class);
        Map alipayResp = (Map) resultMap.get("alipay_trade_query_response");
        PayStatusDto dto = new PayStatusDto();
        dto.setOut_trade_no(payNo);
        dto.setTrade_status((String) alipayResp.get("trade_status"));
        dto.setApp_id(APP_ID);
        dto.setTrade_no((String) alipayResp.get("trade_no"));
        dto.setTotal_amount((String) alipayResp.get("total_amount"));
        return dto;
    }
}
