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

package org.apache.servicecomb.config.client;

import com.fasterxml.jackson.core.type.TypeReference;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.impl.FrameType;
import io.vertx.core.http.impl.ws.WebSocketFrameImpl;
import org.apache.commons.lang.StringUtils;
import org.apache.servicecomb.config.archaius.sources.ConfigCenterConfigurationSourceImpl;
import org.apache.servicecomb.foundation.auth.AuthHeaderLoader;
import org.apache.servicecomb.foundation.auth.SignRequest;
import org.apache.servicecomb.foundation.common.event.EventManager;
import org.apache.servicecomb.foundation.common.net.IpPort;
import org.apache.servicecomb.foundation.common.net.NetUtils;
import org.apache.servicecomb.foundation.common.utils.JsonUtils;
import org.apache.servicecomb.foundation.vertx.client.http.HttpClientWithContext;
import org.apache.servicecomb.foundation.vertx.client.http.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ConfigCenterClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigCenterClient.class);

  private static final ConfigCenterConfig CONFIG_CENTER_CONFIG = ConfigCenterConfig.INSTANCE;

  private static final long HEARTBEAT_INTERVAL = 30000;

  private static final long BOOTUP_WAIT_TIME = 10;

  private ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

  private ScheduledExecutorService heartbeatTask = null;

  private int refreshMode = CONFIG_CENTER_CONFIG.getRefreshMode();

  private int refreshInterval = CONFIG_CENTER_CONFIG.getRefreshInterval();

  private int firstRefreshInterval = CONFIG_CENTER_CONFIG.getFirstRefreshInterval();

  private int refreshPort = CONFIG_CENTER_CONFIG.getRefreshPort();

  private String tenantName = CONFIG_CENTER_CONFIG.getTenantName();

  private String serviceName = CONFIG_CENTER_CONFIG.getServiceName();

  private String environment = CONFIG_CENTER_CONFIG.getEnvironment();
  
  private List<String> fileSources = CONFIG_CENTER_CONFIG.getFileSources();
  
  public List<String> getFileSources() {
    return fileSources;
  }

  private MemberDiscovery memberDiscovery = new MemberDiscovery(CONFIG_CENTER_CONFIG.getServerUri());

  private ConfigCenterConfigurationSourceImpl.UpdateHandler updateHandler;

  private boolean isWatching = false;

  private URIConst uriConst = new URIConst();

  public ConfigCenterClient(ConfigCenterConfigurationSourceImpl.UpdateHandler updateHandler) {
    HttpClients.addNewClientPoolManager(new ConfigCenterHttpClientOptionsSPI());
    this.updateHandler = updateHandler;
  }

  public void connectServer() {
    if (refreshMode != 0 && refreshMode != 1) {
      LOGGER.error("refreshMode must be 0 or 1.");
      return;
    }
    ParseConfigUtils.getInstance().initWithUpdateHandler(updateHandler);
    refreshMembers(memberDiscovery);
    ConfigRefresh refreshTask = new ConfigRefresh(ParseConfigUtils.getInstance(), memberDiscovery);
    refreshTask.run(true);
    executor.scheduleWithFixedDelay(refreshTask,
        firstRefreshInterval,
        refreshInterval,
        TimeUnit.MILLISECONDS);
  }

  public void destroy() {
    if (executor != null) {
      executor.shutdown();
      executor = null;
    }

    if (heartbeatTask != null) {
      heartbeatTask.shutdown();
      heartbeatTask = null;
    }
  }

  private void refreshMembers(MemberDiscovery memberDiscovery) {
    if (CONFIG_CENTER_CONFIG.getAutoDiscoveryEnabled()) {
      String configCenter = memberDiscovery.getConfigServer();
      IpPort ipPort = NetUtils.parseIpPortFromURI(configCenter);
      HttpClients.getClient(ConfigCenterHttpClientOptionsSPI.CLIENT_NAME).runOnContext(client -> {
        @SuppressWarnings("deprecation")
        HttpClientRequest request =
            client.get(ipPort.getPort(), ipPort.getHostOrIp(), uriConst.MEMBERS, rsp -> {
              if (rsp.statusCode() == HttpResponseStatus.OK.code()) {
                rsp.bodyHandler(buf -> {
                  memberDiscovery.refreshMembers(buf.toJsonObject());
                });
              }
            });
        SignRequest signReq = createSignRequest(request.method().toString(),
            configCenter + uriConst.MEMBERS,
            new HashMap<>(),
            null);
        if (ConfigCenterConfig.INSTANCE.getToken() != null) {
          request.headers().add("X-Auth-Token", ConfigCenterConfig.INSTANCE.getToken());
        }
        request.headers()
            .addAll(AuthHeaderLoader.getInstance().loadAuthHeaders(signReq));
        request.exceptionHandler(e -> {
          LOGGER.error("Fetch member from {} failed. Error message is [{}].", configCenter, e.getMessage());
          logIfDnsFailed(e);
        });
        request.end();
      });
    }
  }

  class ConfigRefresh implements Runnable {
    private ParseConfigUtils parseConfigUtils;

    private MemberDiscovery memberdis;

    ConfigRefresh(ParseConfigUtils parseConfigUtils, MemberDiscovery memberdis) {
      this.parseConfigUtils = parseConfigUtils;
      this.memberdis = memberdis;
    }

    public void run(boolean wait) {
      try {
        String configCenter = memberdis.getConfigServer();
        if (refreshMode == 1) {
          //make sure that revision is updated timely,wait sub thread to finish it's pull task
          refreshConfig(configCenter, true);
        } else if (!isWatching) {
          // ??????????????????????????????????????????????????????????????????
          //we do not need worry about that the revision may not be updated timely, because we do not need
          //revision info in the push mode. the config-center will push the changing to us
          refreshConfig(configCenter, wait);
          doWatch(configCenter);
        }
      } catch (Throwable e) {
        LOGGER.error("client refresh thread exception", e);
      }
    }

    // ????????????
    @Override
    public void run() {
      run(false);
    }

    // create watch and wait for done
    public void doWatch(String configCenter)
        throws UnsupportedEncodingException, InterruptedException {
      CountDownLatch waiter = new CountDownLatch(1);
      IpPort ipPort = NetUtils.parseIpPortFromURI(configCenter);
      String url = uriConst.REFRESH_ITEMS + "?dimensionsInfo="
          + StringUtils.deleteWhitespace(URLEncoder.encode(serviceName, "UTF-8"));
      Map<String, String> headers = new HashMap<>();
      headers.put("x-domain-name", tenantName);
      if (ConfigCenterConfig.INSTANCE.getToken() != null) {
        headers.put("X-Auth-Token", ConfigCenterConfig.INSTANCE.getToken());
      }
      headers.put("x-environment", environment);

      HttpClientWithContext vertxHttpClient = HttpClients.getClient(ConfigCenterHttpClientOptionsSPI.CLIENT_NAME);

      vertxHttpClient.runOnContext(client -> {
        Map<String, String> authHeaders = new HashMap<>();
        authHeaders.putAll(AuthHeaderLoader.getInstance().loadAuthHeaders((
            createSignRequest(null, configCenter + url, headers, null))));
        WebSocketConnectOptions options = new WebSocketConnectOptions();
        options.setHost(ipPort.getHostOrIp()).setPort(refreshPort).setURI(url)
            .setHeaders(MultiMap.caseInsensitiveMultiMap().addAll(headers)
                .addAll(authHeaders));
        client.webSocket(options, asyncResult -> {
          if (asyncResult.failed()) {
            LOGGER.error(
                "watcher connect to config center {} refresh port {} failed. Error message is [{}]",
                configCenter,
                refreshPort,
                asyncResult.cause().getMessage());
            waiter.countDown();
          } else {
            {
              asyncResult.result().exceptionHandler(e -> {
                LOGGER.error("watch config read fail", e);
                stopHeartBeatThread();
                isWatching = false;
              });
              asyncResult.result().closeHandler(v -> {
                LOGGER.warn("watching config connection is closed accidentally");
                stopHeartBeatThread();
                isWatching = false;
              });

              asyncResult.result().pongHandler(pong -> {
                // ignore, just prevent NPE.
              });
              asyncResult.result().frameHandler(frame -> {
                if (frame.isText() || frame.isBinary()) {
                  Buffer action = frame.binaryData();
                  LOGGER.debug("watching config received {}", action);
                  Map<String, Object> mAction = null;

                  try {
                    mAction = action.toJsonObject().getMap();
                  } catch (Exception e) {
                    LOGGER.error("parse config item failed.", e);
                    return;
                  }

                  if ("CREATE".equals(mAction.get("action"))) {
                    //event loop can not be blocked,we just keep nothing changed in push mode
                    refreshConfig(configCenter, false);
                  } else if ("MEMBER_CHANGE".equals(mAction.get("action"))) {
                    refreshMembers(memberdis);
                  } else {
                    parseConfigUtils.refreshConfigItemsIncremental(mAction);
                  }
                }
              });
              startHeartBeatThread(asyncResult.result());
              isWatching = true;
              waiter.countDown();
            }
          }
        });
      });
      waiter.await();
    }

    private void startHeartBeatThread(WebSocket ws) {
      heartbeatTask = Executors.newScheduledThreadPool(1);
      heartbeatTask.scheduleWithFixedDelay(() -> sendHeartbeat(ws),
          HEARTBEAT_INTERVAL,
          HEARTBEAT_INTERVAL,
          TimeUnit.MILLISECONDS);
    }

    private void stopHeartBeatThread() {
      if (heartbeatTask != null) {
        heartbeatTask.shutdownNow();
      }
    }

    private void sendHeartbeat(WebSocket ws) {
      try {
        ws.writeFrame(new WebSocketFrameImpl(FrameType.PING));
        EventManager.post(new ConnSuccEvent());
      } catch (IllegalStateException e) {
        EventManager.post(new ConnFailEvent("heartbeat fail, " + e.getMessage()));
        LOGGER.error("heartbeat fail", e);
      }
    }

    public void refreshConfig(String configcenter, boolean wait) {
      CountDownLatch latch = new CountDownLatch(1);
      String encodeServiceName = "";
      try {
        encodeServiceName =
            URLEncoder.encode(StringUtils.deleteWhitespace(serviceName), StandardCharsets.UTF_8.name());
      } catch (UnsupportedEncodingException e) {
        LOGGER.error("encode failed. Error message: {}", e.getMessage());
        encodeServiceName = StringUtils.deleteWhitespace(serviceName);
      }
      //just log in the debug level
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Updating remote config...");
      }
      String path = uriConst.ITEMS + "?dimensionsInfo=" + encodeServiceName + "&revision="
          + ParseConfigUtils.getInstance().getCurrentVersionInfo();
      HttpClients.getClient(ConfigCenterHttpClientOptionsSPI.CLIENT_NAME).runOnContext(client -> {
        IpPort ipPort = NetUtils.parseIpPortFromURI(configcenter);
        @SuppressWarnings("deprecation")
        HttpClientRequest request = client.get(ipPort.getPort(), ipPort.getHostOrIp(), path, rsp -> {
          if (rsp.statusCode() == HttpResponseStatus.OK.code()) {
            rsp.bodyHandler(buf -> {
              try {
                parseConfigUtils
                    .refreshConfigItems(JsonUtils.OBJ_MAPPER.readValue(buf.toString(),
                        new TypeReference<LinkedHashMap<String, Map<String, Object>>>() {
                        }));
                EventManager.post(new ConnSuccEvent());
              } catch (IOException e) {
                EventManager.post(new ConnFailEvent(
                    "config update result parse fail " + e.getMessage()));
                LOGGER.error("Config update from {} failed. Error message is [{}].",
                    configcenter,
                    e.getMessage());
              }
              latch.countDown();
            });
          } else if (rsp.statusCode() == HttpResponseStatus.NOT_MODIFIED.code()) {
            //nothing changed
            EventManager.post(new ConnSuccEvent());
            if (LOGGER.isDebugEnabled()) {
              LOGGER.debug("Updating remote config is done. the revision {} has no change",
                  ParseConfigUtils.getInstance().getCurrentVersionInfo());
            }
            latch.countDown();
          } else {
            rsp.bodyHandler(buf -> {
              LOGGER.error("Server error message is [{}].", buf);
              latch.countDown();
            });
            EventManager.post(new ConnFailEvent("fetch config fail"));
            LOGGER.error("Config update from {} failed.", configcenter);
          }
        }).setTimeout((BOOTUP_WAIT_TIME - 1) * 1000);
        Map<String, String> headers = new HashMap<>();
        headers.put("x-domain-name", tenantName);
        if (ConfigCenterConfig.INSTANCE.getToken() != null) {
          headers.put("X-Auth-Token", ConfigCenterConfig.INSTANCE.getToken());
        }
        headers.put("x-environment", environment);
        request.headers().addAll(headers);
        request.headers()
            .addAll(AuthHeaderLoader.getInstance().loadAuthHeaders(createSignRequest(request.method().toString(),
                configcenter + path,
                headers,
                null)));
        request.exceptionHandler(e -> {
          EventManager.post(new ConnFailEvent("fetch config fail"));
          LOGGER.error("Config update from {} failed. Error message is [{}].",
              configcenter,
              e.getMessage());
          logIfDnsFailed(e);
          latch.countDown();
        });
        request.end();
      });
      if (wait) {
        try {
          latch.await(BOOTUP_WAIT_TIME, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          LOGGER.warn(e.getMessage());
        }
      }
    }
  }

  public static SignRequest createSignRequest(String method, String endpoint, Map<String, String> headers,
      InputStream content) {
    SignRequest signReq = new SignRequest();
    try {
      signReq.setEndpoint(new URI(endpoint));
    } catch (URISyntaxException e) {
      LOGGER.warn("set uri failed, uri is {}, message: {}", endpoint, e.getMessage());
    }

    Map<String, String[]> queryParams = new HashMap<>();
    if (endpoint.contains("?")) {
      String parameters = endpoint.substring(endpoint.indexOf("?") + 1);
      if (null != parameters && !"".equals(parameters)) {
        String[] parameterarray = parameters.split("&");
        for (String p : parameterarray) {
          String key = p.split("=")[0];
          String value = p.split("=")[1];
          if (!queryParams.containsKey(key)) {
            queryParams.put(key, new String[] {value});
          } else {
            List<String> vals = new ArrayList<>(Arrays.asList(queryParams.get(key)));
            vals.add(value);
            queryParams.put(key, vals.toArray(new String[vals.size()]));
          }
        }
      }
    }
    signReq.setQueryParams(queryParams);

    signReq.setHeaders(headers);
    signReq.setHttpMethod(method);
    signReq.setContent(content);
    return signReq;
  }

  private void logIfDnsFailed(Throwable e) {
    if (e instanceof UnknownHostException) {
      LOGGER.error("DNS resolve failed!", e);
    }
  }
}
