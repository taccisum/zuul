/*
 * Copyright 2013 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */
package filters.pre

import com.netflix.config.DynamicPropertyFactory
import com.netflix.config.DynamicStringProperty
import com.netflix.zuul.FilterProcessor
import com.netflix.zuul.ZuulApplicationInfo
import com.netflix.zuul.ZuulFilter
import com.netflix.zuul.constants.ZuulConstants
import com.netflix.zuul.context.NFRequestContext
import com.netflix.zuul.context.RequestContext
import com.netflix.zuul.exception.ZuulException

/**
 * Routing过滤器判断是将请求路由到静态资源还是其它服务
 *
 * @author Mikey Cohen
 * Date: 1/23/13
 * Time: 2:03 PM
 */
class Routing extends ZuulFilter {
    DynamicStringProperty defaultClient = DynamicPropertyFactory.getInstance().getStringProperty(ZuulConstants.ZUUL_NIWS_DEFAULTCLIENT, ZuulApplicationInfo.applicationName);
    DynamicStringProperty defaultHost = DynamicPropertyFactory.getInstance().getStringProperty(ZuulConstants.ZUUL_DEFAULT_HOST, null);


    @Override
    int filterOrder() {
        return 1
    }

    @Override
    String filterType() {
        return "pre"
    }

    boolean shouldFilter() {
        return true
    }


    Object staticRouting() {
        // 路由到静态资源
        FilterProcessor.instance.runFilters("healthcheck")
        FilterProcessor.instance.runFilters("static")
    }

    Object run() {

        staticRouting() //runs the static Zuul

        // TODO:: 这里routeVIP的值是固定的（origin），后面也没有找到修改该值的地方，导致zuul只能路由到某个单一的服务，暂时不知道原因，先mark一下
        // 目标Eureka VIP
        ((NFRequestContext) RequestContext.currentContext).routeVIP = defaultClient.get()
        String host = defaultHost.get()
        if (((NFRequestContext) RequestContext.currentContext).routeVIP == null) ((NFRequestContext) RequestContext.currentContext).routeVIP = ZuulApplicationInfo.applicationName
        if (host != null) {
            final URL targetUrl = new URL(host)
            RequestContext.currentContext.setRouteHost(targetUrl);
            ((NFRequestContext) RequestContext.currentContext).routeVIP = null
        }

        // host与routeVIP不能同时为null
        if (host == null && RequestContext.currentContext.routeVIP == null) {
            throw new ZuulException("default VIP or host not defined. Define: zuul.niws.defaultClient or zuul.default.host", 501, "zuul.niws.defaultClient or zuul.default.host not defined")
        }

        String uri = RequestContext.currentContext.request.getRequestURI()
        // 如果在之前的filter当中给上下文的requestURI赋值了，则覆盖原uri的值
        if (RequestContext.currentContext.requestURI != null) {
            uri = RequestContext.currentContext.requestURI
        }
        if (uri == null) uri = "/"
        if (uri.startsWith("/")) {
            uri = uri - "/"
        }

        // 截取路径的第一段为route
        ((NFRequestContext) RequestContext.currentContext).route = uri.substring(0, uri.indexOf("/") + 1)
    }
}
