package io.wisoft.ignoa_payment.callback.client;

import io.wisoft.ignoa_payment.callback.service.CallbackOutcome;

public interface ApiCallbackClient {

    CallbackOutcome send(Long tradeId, String payload);
}
