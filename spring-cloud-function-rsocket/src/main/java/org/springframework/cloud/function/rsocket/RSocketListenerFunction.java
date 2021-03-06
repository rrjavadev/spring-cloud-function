/*
 * Copyright 2020-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.function.rsocket;

import java.util.Map;
import java.util.function.Function;

import io.rsocket.frame.FrameType;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.function.context.catalog.FunctionTypeUtils;
import org.springframework.cloud.function.context.catalog.SimpleFunctionRegistry.FunctionInvocationWrapper;
import org.springframework.messaging.Message;
import org.springframework.messaging.rsocket.annotation.support.RSocketFrameTypeMessageCondition;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.Assert;

/**
 * A function wrapper which is bound onto an RSocket route.
 *
 * @author Oleg Zhurakousky
 * @author Artem Bilan
 *
 * @since 3.1
 */
class RSocketListenerFunction implements Function<Message<Flux<Object>>, Publisher<?>> {

	private final FunctionInvocationWrapper targetFunction;

	RSocketListenerFunction(FunctionInvocationWrapper targetFunction) {
		this.targetFunction = targetFunction;
	}

	@Override
	public Publisher<?> apply(Message<Flux<Object>> input) {
		Assert.isTrue(this.targetFunction != null, "Failed to discover target function. \n"
				+ "To fix it you should either provide 'spring.cloud.function.definition' property "
				+ "or if you are using RSocketRequester provide valid function definition via 'route' "
				+ "operator (e.g., requester.route(\"echo\"))");
		FrameType frameType = RSocketFrameTypeMessageCondition.getFrameType(input);
		switch (frameType) {
			case REQUEST_FNF:
				return handle(input);
			case REQUEST_RESPONSE:
			case REQUEST_STREAM:
			case REQUEST_CHANNEL:
				return handleAndReply(input);
			default:
				throw new UnsupportedOperationException();
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Mono<Void> handle(Message<Flux<Object>> messageToProcess) {
		if (this.targetFunction.isRoutingFunction()) {
			Flux<?> dataFlux = messageToProcess.getPayload()
					.map((payload) -> {
						return MessageBuilder.createMessage(payload, messageToProcess.getHeaders());
					});
			return dataFlux.doOnNext(this.targetFunction).then();
		}
		else if (this.targetFunction.isConsumer()) {
			Flux<?> dataFlux =
				messageToProcess.getPayload()
					.map((payload) -> MessageBuilder.createMessage(payload, messageToProcess.getHeaders()));
			if (FunctionTypeUtils.isPublisher(this.targetFunction.getInputType())) {
				dataFlux = dataFlux.transform((Function) this.targetFunction);
			}
			else {
				dataFlux = dataFlux.doOnNext(this.targetFunction);
			}
			return dataFlux.then();
		}
		else {
			return Mono.error(new IllegalStateException("Only 'Consumer' can handle 'fire-and-forget' RSocket frame."));
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Flux<?> handleAndReply(Message<Flux<Object>> messageToProcess) {
		Flux<?> dataFlux =
			messageToProcess.getPayload()
				.map((payload) -> {
					if (!(payload instanceof Message)) {
						payload = MessageBuilder.createMessage(payload, messageToProcess.getHeaders());
					}
					return payload;
				});
		if (this.targetFunction.getInputType() != null && FunctionTypeUtils.isPublisher(this.targetFunction.getInputType())) {
			dataFlux = dataFlux.transform((Function) this.targetFunction);
		}
		else {
			dataFlux = dataFlux.flatMap((data) -> {
				Map<String, ?> messageMap = FunctionRSocketUtils.sanitizeMessageToMap((Message<?>) data);
				Message sanitizedMessage = MessageBuilder.withPayload(messageMap.remove(FunctionRSocketUtils.PAYLOAD))
						.copyHeaders((Map<String, ?>) messageMap.get(FunctionRSocketUtils.HEADERS))
						.build();
				Object result = this.targetFunction.isSupplier() ? this.targetFunction.apply(null) : this.targetFunction.apply(sanitizedMessage);
				return result instanceof Publisher<?>
					? (Publisher<?>) result
					: Mono.just(result);
			});
		}
		return dataFlux;
	}
}
