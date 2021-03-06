/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.servicecomb.common.rest;

import java.util.List;

import org.apache.servicecomb.common.rest.filter.HttpServerFilter;
import org.apache.servicecomb.common.rest.locator.OperationLocator;
import org.apache.servicecomb.common.rest.locator.ServicePathManager;
import org.apache.servicecomb.core.SCBEngine;
import org.apache.servicecomb.core.Transport;
import org.apache.servicecomb.core.definition.MicroserviceMeta;
import org.apache.servicecomb.core.invocation.InvocationFactory;
import org.apache.servicecomb.foundation.vertx.http.HttpServletRequestEx;
import org.apache.servicecomb.foundation.vertx.http.HttpServletResponseEx;
import org.apache.servicecomb.swagger.invocation.exception.InvocationException;

public class RestProducerInvocation extends AbstractRestInvocation {

  protected Transport transport;

  public void invoke(Transport transport, HttpServletRequestEx requestEx, HttpServletResponseEx responseEx,
      List<HttpServerFilter> httpServerFilters) {
    this.transport = transport;
    this.requestEx = requestEx;
    this.responseEx = responseEx;
    this.httpServerFilters = httpServerFilters;
    requestEx.setAttribute(RestConst.REST_REQUEST, requestEx);

    try {
      // 寻找接口契约，如果找到匹配的契约信息，将信息保存在当前类的全局变量中，供scheduleInvocation执行方式时使用
      findRestOperation();
    } catch (InvocationException e) {
      sendFailResponse(e);
      return;
    }
    // 真正执行方法
    scheduleInvocation();
  }

  protected void findRestOperation() {
    MicroserviceMeta selfMicroserviceMeta = SCBEngine.getInstance().getProducerMicroserviceMeta();
    findRestOperation(selfMicroserviceMeta);
  }

  @Override
  protected OperationLocator locateOperation(ServicePathManager servicePathManager) {
    return servicePathManager.producerLocateOperation(requestEx.getRequestURI(), requestEx.getMethod());
  }

  @Override
  protected void createInvocation() {
    this.invocation = InvocationFactory.forProvider(transport.getEndpoint(),
        restOperationMeta.getOperationMeta(),
        null);
  }
}
