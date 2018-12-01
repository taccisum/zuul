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

import com.netflix.zuul.ZuulFilter
import com.netflix.zuul.context.NFRequestContext
import com.netflix.zuul.context.RequestContext
import com.netflix.zuul.exception.ZuulException
import com.netflix.zuul.stats.ErrorStatsManager
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.runners.MockitoJUnitRunner
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class ErrorResponse extends ZuulFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorResponse.class);

    @Override
    String filterType() {
        return 'error'
    }

    @Override
    int filterOrder() {
        return 1
    }


    boolean shouldFilter() {
        // 判断错误是否已被处理
        return RequestContext.getCurrentContext().get("ErrorHandled") == null
    }


    Object run() {
        RequestContext context = RequestContext.currentContext
        Throwable ex = context.getThrowable()
        try {
            LOG.error(ex.getMessage(), ex);
            throw ex
        } catch (ZuulException e) {
            String cause = e.errorCause
            if (cause == null) cause = "UNKNOWN"
            // 添加错误原因到请求头X-Netflix-Error-Cause
            RequestContext.getCurrentContext().getResponse().addHeader("X-Netflix-Error-Cause", "Zuul Error: " + cause)
            // 对该次错误请求进行统计
            if (e.nStatusCode == 404) {
                ErrorStatsManager.manager.putStats("ROUTE_NOT_FOUND", "")
            } else {
                ErrorStatsManager.manager.putStats(RequestContext.getCurrentContext().route, "Zuul_Error_" + cause)
            }

            // 判断是否改写响应状态，则请求传入的参数决定
            if (overrideStatusCode) {
                RequestContext.getCurrentContext().setResponseStatusCode(200);
            } else {
                RequestContext.getCurrentContext().setResponseStatusCode(e.nStatusCode);
            }
            // 设置标识，表示不再返回zuul转发请求得到的响应结果（如果有）
            context.setSendZuulResponse(false)
            // 设置zuul的异常响应body
            context.setResponseBody("${getErrorMessage(e, e.nStatusCode)}")
        } catch (Throwable throwable) {
            // 处理未知异常，与处理ZuulException的逻辑大体相同
            RequestContext.getCurrentContext().getResponse().addHeader("X-Zuul-Error-Cause", "Zuul Error UNKNOWN Cause")
            ErrorStatsManager.manager.putStats(RequestContext.getCurrentContext().route, "Zuul_Error_UNKNOWN_Cause")

            if (overrideStatusCode) {
                RequestContext.getCurrentContext().setResponseStatusCode(200);
            } else {
                RequestContext.getCurrentContext().setResponseStatusCode(500);
            }
            context.setSendZuulResponse(false)
            context.setResponseBody("${getErrorMessage(throwable, 500)}")
        } finally {
            // 设置标识，表示错误已经被处理（防止存在多个error过滤器时重复处理）
            context.set("ErrorHandled") //ErrorResponse was handled
            return null;
        }
    }
    /*
    JSON/ xml ErrorResponse responses

v=1 or unspecified:
<status>
<status_code>status_code</status_code>
<message>message</message>
</status>

v=1.5,2.0:
<status>
<message>user_id is invalid</message>
</status>

v=1.5,2.0:
{"status": {"message": "user_id is invalid"}}

v=1 or unspecified:

     */

    String getErrorMessage(Throwable ex, int status_code) {
        String ver = version
        String format = outputType
        switch (ver) {
            case '1':
            case '1.0':
                switch (format) {
                    case 'json':
                        RequestContext.getCurrentContext().getResponse().setContentType('application/json')
                        String response = """{"status": {"message": "${ex.message}", "status_code": ${status_code}}}"""
                        if (callback) {
                            response = callback + "(" + response + ");"
                        }
                        return response
                    case 'xml':
                    default:
                        RequestContext.getCurrentContext().getResponse().setContentType('application/xml')
                        return """<status>
  <status_code>${status_code}</status_code>
  <message>${ex.message}</message>
</status>"""
                }
                break;
            case '1.5':
            case '2.0':
            default:
                switch (format) {
                    case 'json':
                        RequestContext.getCurrentContext().getResponse().setContentType('application/json')
                        String response = """{"status": {"message": "${ex.message}"}}"""
                        if (callback) {
                            response = callback + "(" + response + ");"
                        }
                        return response
                    case 'xml':
                    default:
                        RequestContext.getCurrentContext().getResponse().setContentType('application/xml')
                        return """<status>
<message>${ex.message}</message>
</status>"""
                }
                break;

        }

    }

    boolean getOverrideStatusCode() {
        String override = RequestContext.currentContext.getRequest().getParameter("override_error_status")
        if (callback != null) return true;
        if (override == null) return false
        return Boolean.valueOf(override)

    }

    String getCallback() {
        String callback = RequestContext.currentContext.getRequest().getParameter("callback")
        if (callback == null) return null;
        return callback;
    }

    String getOutputType() {
        String output = RequestContext.currentContext.getRequest().getParameter("output")
        if (output == null) return "xml"
        return output;
    }

    String getVersion() {
        String version = RequestContext.currentContext.getRequest().getParameter("v")
        if (version == null) return "1"
        if (overrideStatusCode) return "1"
        return version;
    }


    @RunWith(MockitoJUnitRunner.class)
    public static class TestUnit {

        @Mock
        HttpServletResponse response
        @Mock
        HttpServletRequest request

        @Test
        public void testErrorXMLv10() {
            RequestContext.setContextClass(NFRequestContext.class);
            ErrorResponse errorResponse = new ErrorResponse();
            HttpServletRequest request = Mockito.mock(HttpServletRequest.class)
            RequestContext.currentContext.request = request
            RequestContext.currentContext.response = response
            Mockito.when(request.getParameter("v")).thenReturn("1.0")
            Mockito.when(request.getParameter("override_error_status")).thenReturn("true")
            Throwable th = new Exception("test")
            RequestContext.currentContext.throwable = th;
            errorResponse.run();
            Assert.assertTrue(RequestContext.currentContext.getResponseBody().contains("<message>test</message>"))
            Assert.assertTrue(RequestContext.currentContext.getResponseBody().contains("<status_code>500</status_code>"))
            Assert.assertTrue(RequestContext.getCurrentContext().responseStatusCode == 200)
        }

        @Test
        public void testErrorXMLv10OverrideErrorStatus() {
            RequestContext.setContextClass(NFRequestContext.class);
            ErrorResponse errorResponse = new ErrorResponse();
            HttpServletRequest request = Mockito.mock(HttpServletRequest.class)
            RequestContext.currentContext.request = request
            RequestContext.currentContext.response = response
            Mockito.when(request.getParameter("v")).thenReturn("1.0")
            Throwable th = new Exception("test")
            RequestContext.currentContext.throwable = th;
            errorResponse.run();
            Assert.assertTrue(RequestContext.currentContext.getResponseBody().contains("<message>test</message>"))
            Assert.assertTrue(RequestContext.currentContext.getResponseBody().contains("<status_code>500</status_code>"))
            Assert.assertTrue(RequestContext.getCurrentContext().responseStatusCode == 500)
        }


        @Test
        public void testErrorXML() {
            RequestContext.setContextClass(NFRequestContext.class);
            RequestContext.currentContext.unset();
            ErrorResponse errorResponse = new ErrorResponse();
            HttpServletRequest request = Mockito.mock(HttpServletRequest.class)
            RequestContext.currentContext.request = request
            RequestContext.currentContext.response = response
            Throwable th = new Exception("test")
            RequestContext.currentContext.throwable = th;
            errorResponse.run();
            Assert.assertTrue(RequestContext.currentContext.getResponseBody().contains("<message>test</message>"))
            Assert.assertTrue(RequestContext.currentContext.getResponseBody().contains("<status_code>500</status_code>"))
            Assert.assertTrue(RequestContext.getCurrentContext().responseStatusCode == 500)
        }

        @Test
        public void testErrorXMLv20() {
            RequestContext.setContextClass(NFRequestContext.class);
            RequestContext.currentContext.unset();
            ErrorResponse errorResponse = new ErrorResponse();
            HttpServletRequest request = Mockito.mock(HttpServletRequest.class)
            RequestContext.currentContext.request = request
            RequestContext.currentContext.response = response
            Mockito.when(request.getParameter("v")).thenReturn("2.0")
            Throwable th = new Exception("test")
            RequestContext.currentContext.throwable = th;
            errorResponse.run();
            Assert.assertTrue(RequestContext.currentContext.getResponseBody().contains("<message>test</message>"))
            Assert.assertTrue(!RequestContext.currentContext.getResponseBody().contains("<status_code>500</status_code>"))
            Assert.assertTrue(RequestContext.getCurrentContext().responseStatusCode == 500)
        }

        @Test
        public void testErrorJSON() {
            RequestContext.setContextClass(NFRequestContext.class);
            RequestContext.currentContext.unset();
            ErrorResponse errorResponse = new ErrorResponse();
            HttpServletRequest request = Mockito.mock(HttpServletRequest.class)
            RequestContext.currentContext.request = request
            RequestContext.currentContext.response = response
            Mockito.when(request.getParameter("output")).thenReturn("json")
            Throwable th = new Exception("test")
            RequestContext.currentContext.throwable = th;
            errorResponse.run();

            Assert.assertTrue(RequestContext.currentContext.getResponseBody().equals("{\"status\": {\"message\": \"test\", \"status_code\": 500}}"))
            Assert.assertTrue(RequestContext.getCurrentContext().responseStatusCode == 500)
        }

        @Test
        public void testErrorJSONv20() {
            RequestContext.setContextClass(NFRequestContext.class);
            RequestContext.currentContext.unset();
            ErrorResponse errorResponse = new ErrorResponse();
            HttpServletRequest request = Mockito.mock(HttpServletRequest.class)
            RequestContext.currentContext.request = request
            RequestContext.currentContext.response = response
            Mockito.when(request.getParameter("output")).thenReturn("json")
            Mockito.when(request.getParameter("v")).thenReturn("2.0")
            Throwable th = new Exception("test")
            RequestContext.currentContext.throwable = th;
            errorResponse.run();
            Assert.assertTrue(RequestContext.currentContext.getResponseBody().equals("{\"status\": {\"message\": \"test\"}}"))
            Assert.assertTrue(RequestContext.getCurrentContext().responseStatusCode == 500)
        }


        @Test
        public void testErrorJSONv20Callback() {
            RequestContext.setContextClass(NFRequestContext.class);
            RequestContext.currentContext.unset();
            ErrorResponse errorResponse = new ErrorResponse();
            HttpServletRequest request = Mockito.mock(HttpServletRequest.class)
            RequestContext.currentContext.request = request
            RequestContext.currentContext.response = response
            Mockito.when(request.getParameter("output")).thenReturn("json")
            Mockito.when(request.getParameter("v")).thenReturn("2.0")
            Mockito.when(request.getParameter("callback")).thenReturn("moo")
            Throwable th = new Exception("test")
            RequestContext.currentContext.throwable = th;
            errorResponse.run();
            GroovyTestCase.assertEquals("moo({\"status\": {\"message\": \"test\", \"status_code\": 500}});", RequestContext.currentContext.getResponseBody())
            GroovyTestCase.assertEquals(200, RequestContext.getCurrentContext().responseStatusCode)
        }

        @Test
        public void testErrorJSONCallback() {
            RequestContext.setContextClass(NFRequestContext.class);
            RequestContext.currentContext.unset();
            ErrorResponse errorResponse = new ErrorResponse();
            HttpServletRequest request = Mockito.mock(HttpServletRequest.class)
            RequestContext.currentContext.request = request
            RequestContext.currentContext.response = response
            Mockito.when(request.getParameter("output")).thenReturn("json")
            Mockito.when(request.getParameter("callback")).thenReturn("moo")
            Throwable th = new Exception("test")
            RequestContext.currentContext.throwable = th;
            errorResponse.run();
            GroovyTestCase.assertEquals("moo({\"status\": {\"message\": \"test\", \"status_code\": 500}});", RequestContext.currentContext.getResponseBody())
            GroovyTestCase.assertEquals(200, RequestContext.getCurrentContext().responseStatusCode)
        }


        @Test
        public void testErrorJSONv20OverrideErrorStatus() {
            RequestContext.setContextClass(NFRequestContext.class);
            RequestContext.currentContext.unset();
            ErrorResponse errorResponse = new ErrorResponse();
            HttpServletRequest request = Mockito.mock(HttpServletRequest.class)
            RequestContext.currentContext.request = request
            RequestContext.currentContext.response = response
            Mockito.when(request.getParameter("output")).thenReturn("json")
            Mockito.when(request.getParameter("v")).thenReturn("2.0")
            Mockito.when(request.getParameter("override_error_status")).thenReturn("true")
            Throwable th = new Exception("test")
            RequestContext.currentContext.throwable = th;
            errorResponse.run();
            GroovyTestCase.assertEquals("{\"status\": {\"message\": \"test\", \"status_code\": 500}}", RequestContext.currentContext.getResponseBody())
            GroovyTestCase.assertEquals(200, RequestContext.getCurrentContext().responseStatusCode)
        }


    }


}
