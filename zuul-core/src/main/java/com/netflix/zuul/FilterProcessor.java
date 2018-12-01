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
package com.netflix.zuul;

import com.netflix.zuul.context.Debug;
import com.netflix.zuul.context.RequestContext;
import com.netflix.zuul.exception.ZuulException;
import com.netflix.zuul.monitoring.MonitoringHelper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.runners.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

import com.netflix.servo.monitor.DynamicCounter;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * 执行zuul filters的核心类
 * This the the core class to execute filters.
 *
 * @author Mikey Cohen
 *         Date: 10/24/11
 *         Time: 12:47 PM
 */
public class FilterProcessor {

    static FilterProcessor INSTANCE = new FilterProcessor();
    protected static final Logger logger = LoggerFactory.getLogger(FilterProcessor.class);

    private FilterUsageNotifier usageNotifier;


    public FilterProcessor() {
        usageNotifier = new BasicFilterUsageNotifier();
    }

    /**
     * @return the singleton FilterProcessor
     */
    public static FilterProcessor getInstance() {
        return INSTANCE;
    }

    /**
     * sets a singleton processor in case of a need to override default behavior
     *
     * @param processor
     */
    public static void setProcessor(FilterProcessor processor) {
        INSTANCE = processor;
    }

    /**
     * Override the default filter usage notification impl.
     *
     * @param notifier
     */
    public void setFilterUsageNotifier(FilterUsageNotifier notifier) {
        this.usageNotifier = notifier;
    }

    /**
     * runs "post" filters which are called after "route" filters. ZuulExceptions from ZuulFilters are thrown.
     * Any other Throwables are caught and a ZuulException is thrown out with a 500 status code
     *
     * @throws ZuulException
     */
    public void postRoute() throws ZuulException {
        try {
            runFilters("post");
        } catch (ZuulException e) {
            throw e;
        } catch (Throwable e) {
            throw new ZuulException(e, 500, "UNCAUGHT_EXCEPTION_IN_POST_FILTER_" + e.getClass().getName());
        }
    }

    /**
     * runs all "error" filters. These are called only if an exception occurs. Exceptions from this are swallowed and logged so as not to bubble up.
     */
    public void error() {
        try {
            runFilters("error");
        } catch (Throwable e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * Runs all "route" filters. These filters route calls to an origin.
     *
     * @throws ZuulException if an exception occurs.
     */
    public void route() throws ZuulException {
        try {
            runFilters("route");
        } catch (ZuulException e) {
            throw e;
        } catch (Throwable e) {
            throw new ZuulException(e, 500, "UNCAUGHT_EXCEPTION_IN_ROUTE_FILTER_" + e.getClass().getName());
        }
    }

    /**
     * runs all "pre" filters. These filters are run before routing to the orgin.
     *
     * @throws ZuulException
     */
    public void preRoute() throws ZuulException {
        try {
            // filter的类型是居然是写死的...至少也提供个枚举类嘛？
            runFilters("pre");
        } catch (ZuulException e) {
            throw e;
        } catch (Throwable e) {
            throw new ZuulException(e, 500, "UNCAUGHT_EXCEPTION_IN_PRE_FILTER_" + e.getClass().getName());
        }
    }

    /**
     * 这个是真正执行filter的方法，每调用一次都会执行同一类型的所有filter
     * runs all filters of the filterType sType/ Use this method within filters to run custom filters by type
     *
     * @param sType the filterType.
     * @return
     * @throws Throwable throws up an arbitrary exception
     */
    public Object runFilters(String sType) throws Throwable {
        // 添加debug信息
        if (RequestContext.getCurrentContext().debugRouting()) {
            Debug.addRoutingDebug("Invoking {" + sType + "} type filters");
        }
        boolean bResult = false;
        // TODO:: FilterLoader工作方式，后续了解
        // 通过FilterLoader获取指定类型的所有filter
        List<ZuulFilter> list = FilterLoader.getInstance().getFiltersByType(sType);
        if (list != null) {
            // 这里没有进行try...catch... 意味着只要任何一个filter执行失败了整个过程就会中断掉
            for (int i = 0; i < list.size(); i++) {
                ZuulFilter zuulFilter = list.get(i);
                Object result = processZuulFilter(zuulFilter);
                if (result != null && result instanceof Boolean) {
                    // 注意这里写的是|=不是!=
                    // TODO:: 为什么要用这种写法？既然只有result为boolean类型时才执行，直接赋值不行吗
                    bResult |= ((Boolean) result);
                }
            }
        }
        return bResult;
    }

    /**
     * 执行单个zuul filter
     * Processes an individual ZuulFilter. This method adds Debug information. Any uncaught Thowables are caught by this method and converted to a ZuulException with a 500 status code.
     *
     * @param filter
     * @return the return value for that filter
     * @throws ZuulException
     */
    public Object processZuulFilter(ZuulFilter filter) throws ZuulException {

        RequestContext ctx = RequestContext.getCurrentContext();
        boolean bDebug = ctx.debugRouting();
        final String metricPrefix = "zuul.filter-";     // 这个变量没有用到...
        long execTime = 0;
        String filterName = "";
        try {
            long ltime = System.currentTimeMillis();
            filterName = filter.getClass().getSimpleName();

            RequestContext copy = null;
            Object o = null;
            Throwable t = null;

            if (bDebug) {
                Debug.addRoutingDebug("Filter " + filter.filterType() + " " + filter.filterOrder() + " " + filterName);
                // copy了一份context用于debug
                copy = ctx.copy();
            }

            ZuulFilterResult result = filter.runFilter();
            ExecutionStatus s = result.getStatus();

            // 统计执行时间
            execTime = System.currentTimeMillis() - ltime;

            // 这段对过滤器的执行状态进行记录
            switch (s) {
                case FAILED:
                    t = result.getException();
                    ctx.addFilterExecutionSummary(filterName, ExecutionStatus.FAILED.name(), execTime);
                    break;
                case SUCCESS:
                    o = result.getResult();
                    ctx.addFilterExecutionSummary(filterName, ExecutionStatus.SUCCESS.name(), execTime);
                    if (bDebug) {
                        Debug.addRoutingDebug("Filter {" + filterName + " TYPE:" + filter.filterType() + " ORDER:" + filter.filterOrder() + "} Execution time = " + execTime + "ms");
                        Debug.compareContextState(filterName, copy);
                    }
                    break;
                default:
                    break;
            }

            if (t != null) throw t;

            // 触发一个filter usage回调
            // 当前notifier的实现固定是BasicFilterUsageNotifier，通过Servo统计filter的调用
            usageNotifier.notify(filter, s);
            return o;

        } catch (Throwable e) {
            if (bDebug) {
                Debug.addRoutingDebug("Running Filter failed " + filterName + " type:" + filter.filterType() + " order:" + filter.filterOrder() + " " + e.getMessage());
            }
            usageNotifier.notify(filter, ExecutionStatus.FAILED);
            if (e instanceof ZuulException) {
                throw (ZuulException) e;
            } else {
                ZuulException ex = new ZuulException(e, "Filter threw Exception", 500, filter.filterType() + ":" + filterName);
                // 如果在line210之前抛出了异常，这个execTime的值会是0
                // 不过ZuulFilter.runFilter()中做了try...catch...处理，理论上来说不会出现异常
                ctx.addFilterExecutionSummary(filterName, ExecutionStatus.FAILED.name(), execTime);
                throw ex;
            }
        }
    }

    /**
     * Publishes a counter metric for each filter on each use.
     */
    public static class BasicFilterUsageNotifier implements FilterUsageNotifier {
        private static final String METRIC_PREFIX = "zuul.filter-";

        @Override
        public void notify(ZuulFilter filter, ExecutionStatus status) {
            // 通过Netflix Servo对每个filter进行调用计数
            DynamicCounter.increment(METRIC_PREFIX + filter.getClass().getSimpleName(), "status", status.name(), "filtertype", filter.filterType());
        }
    }

    @RunWith(MockitoJUnitRunner.class)
    public static class UnitTest {

        @Mock
        ZuulFilter filter;

        @Before
        public void before() {
            MonitoringHelper.initMocks();
            MockitoAnnotations.initMocks(this);
        }

        @Test
        public void testProcessZuulFilter() {
            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);
            try {
                processor.processZuulFilter(filter);
                verify(processor, times(1)).processZuulFilter(filter);
                verify(filter, times(1)).runFilter();

            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        @Test
        public void testProcessZuulFilterException() {
            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);

            try {
                ZuulFilterResult r = new ZuulFilterResult(ExecutionStatus.FAILED);
                r.setException(new Exception("Test"));
                when(filter.runFilter()).thenReturn(r);
                when(filter.filterType()).thenReturn("post");
                processor.processZuulFilter(filter);
                assertFalse(true);
            } catch (Throwable e) {
                assertEquals(e.getCause().getMessage(), "Test");
            }
        }


        @Test
        public void testPostProcess() {
            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);
            try {
                processor.postRoute();
                verify(processor, times(1)).runFilters("post");
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        @Test
        public void testPreProcess() {
            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);
            try {
                processor.preRoute();
                verify(processor, times(1)).runFilters("pre");
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        @Test
        public void testRouteProcess() {
            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);
            try {
                processor.route();
                verify(processor, times(1)).runFilters("route");
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        @Test
        public void testRouteProcessHttpException() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            RequestContext.getCurrentContext().setRequest(request);
            RequestContext.getCurrentContext().setResponse(response);

            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);
            try {
                when(processor.runFilters("route")).thenThrow(new ZuulException("test", 400, "test"));
                when(filter.filterType()).thenReturn("post");
                processor.route();
            } catch (ZuulException e) {
                assertEquals(e.getMessage(), "test");
                assertEquals(e.nStatusCode, 400);
            } catch (Throwable e) {
                e.printStackTrace();
                assertFalse(true);

            }

        }

        @Test
        public void testRouteProcessException() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            RequestContext.getCurrentContext().setRequest(request);
            RequestContext.getCurrentContext().setResponse(response);

            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);

            try {
                when(processor.runFilters("route")).thenThrow(new Throwable("test"));
                when(filter.filterType()).thenReturn("post");
                processor.route();
            } catch (ZuulException e) {
                assertEquals(e.getMessage(), "test");
                assertEquals(e.nStatusCode, 500);
            } catch (Throwable e) {
                assertFalse(true);
            }

        }

        @Test
        public void testPreProcessException() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            RequestContext.getCurrentContext().setRequest(request);
            RequestContext.getCurrentContext().setResponse(response);

            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);

            try {
                when(processor.runFilters("pre")).thenThrow(new Throwable("test"));
                when(filter.filterType()).thenReturn("post");
                processor.preRoute();
            } catch (ZuulException e) {
                assertEquals(e.getMessage(), "test");
                assertEquals(e.nStatusCode, 500);
            } catch (Throwable e) {
                assertFalse(true);
            }

        }

        @Test
        public void testPreProcessHttpException() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            RequestContext.getCurrentContext().setRequest(request);
            RequestContext.getCurrentContext().setResponse(response);

            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);
            try {
                when(processor.runFilters("pre")).thenThrow(new ZuulException("test", 400, "test"));
                when(filter.filterType()).thenReturn("post");
                processor.preRoute();
            } catch (ZuulException e) {
                assertEquals(e.getMessage(), "test");
                assertEquals(e.nStatusCode, 400);
            } catch (Throwable e) {
                e.printStackTrace();
                assertFalse(true);

            }

        }


        @Test
        public void testPostProcessException() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            RequestContext.getCurrentContext().setRequest(request);
            RequestContext.getCurrentContext().setResponse(response);

            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);

            try {
                when(processor.runFilters("post")).thenThrow(new Throwable("test"));
                when(filter.filterType()).thenReturn("post");
                processor.postRoute();
            } catch (ZuulException e) {
                assertEquals(e.getMessage(), "test");
                assertEquals(e.nStatusCode, 500);
            } catch (Throwable e) {
                assertFalse(true);
            }

        }

        @Test
        public void testPostProcessHttpException() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            RequestContext.getCurrentContext().setRequest(request);
            RequestContext.getCurrentContext().setResponse(response);

            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);
            try {
                when(processor.runFilters("post")).thenThrow(new ZuulException("test", 400, "test"));
                when(filter.filterType()).thenReturn("post");
                processor.postRoute();
            } catch (ZuulException e) {
                assertEquals(e.getMessage(), "test");
                assertEquals(e.nStatusCode, 400);
            } catch (Throwable e) {
                e.printStackTrace();
                assertFalse(true);

            }

        }


        @Test
        public void testErrorException() {
            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);

            try {
                when(processor.runFilters("error")).thenThrow(new Exception("test"));
                when(filter.filterType()).thenReturn("post");
                processor.error();
                assertTrue(true);
            } catch (Throwable e) {
                assertFalse(true);
            }

        }

        @Test
        public void testErrorHttpException() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            RequestContext.getCurrentContext().setRequest(request);
            RequestContext.getCurrentContext().setResponse(response);

            FilterProcessor processor = new FilterProcessor();
            processor = spy(processor);
            try {
                when(processor.runFilters("error")).thenThrow(new ZuulException("test", 400, "test"));
                when(filter.filterType()).thenReturn("post");
                processor.error();
                assertTrue(true);
            } catch (Throwable e) {
                e.printStackTrace();
                assertFalse(true);

            }

        }
    }
}
