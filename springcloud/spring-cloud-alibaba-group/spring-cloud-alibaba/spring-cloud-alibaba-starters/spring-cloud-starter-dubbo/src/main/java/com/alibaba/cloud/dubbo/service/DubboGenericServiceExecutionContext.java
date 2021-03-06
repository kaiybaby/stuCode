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

package com.alibaba.cloud.dubbo.service;

import org.apache.dubbo.rpc.service.GenericService;

/**
 * Dubbo {@link GenericService} execution context.
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy</a>
 */
public class DubboGenericServiceExecutionContext {

	private final String methodName;

	private final String[] parameterTypes;

	private final Object[] parameters;

	public DubboGenericServiceExecutionContext(String methodName, String[] parameterTypes,
			Object[] parameters) {
		this.methodName = methodName;
		this.parameterTypes = parameterTypes;
		this.parameters = parameters;
	}

	public String getMethodName() {
		return methodName;
	}

	public String[] getParameterTypes() {
		return parameterTypes;
	}

	public Object[] getParameters() {
		return parameters;
	}

}
