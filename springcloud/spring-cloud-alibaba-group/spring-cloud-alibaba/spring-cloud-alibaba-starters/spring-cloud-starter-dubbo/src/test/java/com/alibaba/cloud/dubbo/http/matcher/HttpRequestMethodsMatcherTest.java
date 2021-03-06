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

package com.alibaba.cloud.dubbo.http.matcher;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Assert;

import org.springframework.http.HttpMethod;

/**
 * {@link HttpRequestMethodsMatcher} Test.
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy</a>
 */
public class HttpRequestMethodsMatcherTest
		extends AbstractHttpRequestMatcherTest<HttpRequestMethodsMatcher> {

	HttpRequestMethodsMatcher matcher = new HttpRequestMethodsMatcher("GET");

	@Override
	public void testEqualsAndHashCode() {
		Assert.assertEquals(new HashSet<>(Arrays.asList(HttpMethod.GET)),
				matcher.getMethods());
	}

	@Override
	public void testGetContent() {
		Assert.assertEquals(new HashSet<>(Arrays.asList(HttpMethod.GET)),
				matcher.getContent());
	}

	@Override
	public void testGetToStringInfix() {
		Assert.assertEquals(" || ", matcher.getToStringInfix());
	}

}
