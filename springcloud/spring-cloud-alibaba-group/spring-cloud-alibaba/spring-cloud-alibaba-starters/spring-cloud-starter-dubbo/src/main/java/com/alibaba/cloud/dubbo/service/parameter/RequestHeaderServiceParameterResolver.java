/*
 * Copyright 2013-2018 the original author or authors.
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

package com.alibaba.cloud.dubbo.service.parameter;

import com.alibaba.cloud.dubbo.http.HttpServerRequest;

import org.springframework.util.MultiValueMap;

/**
 * HTTP Request Header {@link DubboGenericServiceParameterResolver Dubbo GenericService
 * Parameter Resolver}.
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy</a>
 */
public class RequestHeaderServiceParameterResolver
		extends AbstractNamedValueServiceParameterResolver {

	/**
	 * default order.
	 */
	public static final int DEFAULT_ORDER = 9;

	public RequestHeaderServiceParameterResolver() {
		super();
		setOrder(DEFAULT_ORDER);
	}

	@Override
	protected MultiValueMap<String, String> getNameAndValuesMap(
			HttpServerRequest request) {
		return request.getHeaders();
	}

}
