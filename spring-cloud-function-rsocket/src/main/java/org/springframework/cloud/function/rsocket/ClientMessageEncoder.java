/*
 * Copyright 2021-2021 the original author or authors.
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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.cloud.function.context.catalog.FunctionTypeUtils;
import org.springframework.cloud.function.json.JsonMapper;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StreamUtils;

/**
 *
 * @author Oleg Zhurakousky
 * @since 3.1
 *
 */
class ClientMessageEncoder extends Jackson2JsonEncoder {

	/**
	 * The default buffer size used by the encoder.
	 */
	public static final int DEFAULT_BUFFER_SIZE = StreamUtils.BUFFER_SIZE;


	private final JsonMapper mapper;


	ClientMessageEncoder(JsonMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public boolean canEncode(ResolvableType elementType, MimeType mimeType) {
		return FunctionTypeUtils.isMessage(elementType.getType())
				|| Map.class.isAssignableFrom(FunctionTypeUtils.getRawType(elementType.getType()));
	}


	@Override
	public List<MimeType> getEncodableMimeTypes() {
		return Collections.singletonList(MimeTypeUtils.APPLICATION_JSON);
	}

	@Override
	public DataBuffer encodeValue(Object value, DataBufferFactory bufferFactory,
			ResolvableType valueType, @Nullable MimeType mimeType, @Nullable Map<String, Object> hints) {

		if (value instanceof Message) {
			value = FunctionRSocketUtils.sanitizeMessageToMap((Message<?>) value);
		}
		else if (!(value instanceof Map)) {
			if (JsonMapper.isJsonString(value)) {
				value = this.mapper.fromJson(value, valueType.getType());
			}
			value = Collections.singletonMap(FunctionRSocketUtils.PAYLOAD, value);
		}
		return super.encodeValue(value, bufferFactory, valueType, mimeType, hints);
	}
}
