/**
 *
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.solbase;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.CoreContainer.Initializer;
import org.apache.solr.core.SolbaseCoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.BinaryQueryResponseWriter;
import org.apache.solr.request.QueryResponseWriter;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryResponse;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.servlet.SolrDispatchFilter;
import org.apache.solr.servlet.cache.Method;

public class SolbaseDispatchFilter extends SolrDispatchFilter
{

    private static final String schemaPrefix = "/schema";
    private static final String pingPrefix = "/ping";
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException,
            ServletException
    {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String indexName = "";
        String path = req.getServletPath();

        if (req.getPathInfo() != null)
        {
            // this lets you handle /update/commit when /update is a servlet
            path += req.getPathInfo();
        }
        
        // pinging to get sharding information
        if(path.startsWith(pingPrefix)){
        	indexName = parseIndexname(path, pingPrefix);
            try
            {
            	int numShard = SolbaseShardUtil.getNumShard();
            	List<String> hosts = SolbaseShardUtil.getShardHosts();
            	int numPerShard = SolbaseShardUtil.getNumPerShard();
            	
                response.setContentType("text/xml");
                PrintWriter out = resp.getWriter();

                out.print("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                out.print("<result>");
                out.print("<numshard>" + numShard + "</numshard>");
                out.print("<numpershard>" + numPerShard + "</numpershard>");
                
                for(String host: hosts){
                	out.print("<host>"+host+"</host>");
                }
                out.print("</result>");
            }
            catch (IOException e)
            {
                resp.sendError(404);
            }

        	return;
        }
        
        // schema reading/putting
        if (path.startsWith(schemaPrefix))
        {
        	indexName = parseIndexname(path, schemaPrefix);

            // REST
            String method = req.getMethod().toUpperCase();

            if (method.equals("GET"))
            {
                try
                {
                    String schema = SolbaseCoreContainer.readSchemaXML(indexName);
                    response.setContentType("text/xml");
                    PrintWriter out = resp.getWriter();

                    out.print(schema);

                }
                catch (IOException e)
                {
                    resp.sendError(404);
                }

                return;
            }

            if (method.equals("POST") || method.equals("PUT"))
            {
                try
                {

                    BufferedReader rd = new BufferedReader(new InputStreamReader(req.getInputStream()));
                    String line;
                    String xml = "";
                    while ((line = rd.readLine()) != null)
                    {
                        xml += line + "\n";
                    }

                    SolbaseCoreContainer.writeSchema(indexName, xml);

                }
                catch (IOException e)
                {
                    resp.sendError(500);
                }
                return;
            }
        }

        super.doFilter(request, response, chain);
    }
   
    protected String parseIndexname(String path, String prefix){
        path = path.substring(prefix.length());

        // otherwise, we should find a index from the path
        int idx = path.indexOf("/", 1);
        if (idx > 1)
        {
            // try to get the index as a request parameter first
            return path.substring(1, idx);
        }
        else
        {
            return path.substring(1);
        }

    }

    @Override
    protected Initializer createInitializer()
    {
        SolbaseInitializer init = new SolbaseInitializer();

        return init;
    }

    @Override
    protected void execute(HttpServletRequest req, SolrRequestHandler handler, SolrQueryRequest sreq,
            SolrQueryResponse rsp)
    {

        String path = req.getServletPath();
        if (req.getPathInfo() != null)
        {
            // this lets you handle /update/commit when /update is a servlet
            path += req.getPathInfo();
        }
        if (pathPrefix != null && path.startsWith(pathPrefix))
        {
            path = path.substring(pathPrefix.length());
        }

        int idx = path.indexOf("/", 1);
        if (idx > 1)
        {
            // try to get the corename as a request parameter first
            sreq.getContext().put("solbase-index", path.substring(1, idx));
        }

        super.execute(req, handler, sreq, rsp);
    }

    @SuppressWarnings({ "unused", "unchecked" })
	private void handleAdminRequest(HttpServletRequest req, ServletResponse response, SolrRequestHandler handler,
            SolrQueryRequest solrReq) throws IOException
    {
        SolrQueryResponse solrResp = new SolrQueryResponse();
        final NamedList<Object> responseHeader = new SimpleOrderedMap<Object>();
        solrResp.add("responseHeader", responseHeader);
		NamedList<Object> toLog = solrResp.getToLog();
        toLog.add("webapp", req.getContextPath());
        toLog.add("path", solrReq.getContext().get("path"));
        toLog.add("params", "{" + solrReq.getParamString() + "}");
        handler.handleRequest(solrReq, solrResp);
        SolrCore.setResponseHeaderValues(handler, solrReq, solrResp);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toLog.size(); i++)
        {
            String name = toLog.getName(i);
            Object val = toLog.getVal(i);
            sb.append(name).append("=").append(val).append(" ");
        }
        QueryResponseWriter respWriter = SolrCore.DEFAULT_RESPONSE_WRITERS
                .get(solrReq.getParams().get(CommonParams.WT));
        if (respWriter == null)
            respWriter = SolrCore.DEFAULT_RESPONSE_WRITERS.get("standard");
        writeResponse(solrResp, response, respWriter, solrReq, Method.getMethod(req.getMethod()));
    }
    
    private void writeResponse(SolrQueryResponse solrRsp, ServletResponse response, QueryResponseWriter responseWriter,
            SolrQueryRequest solrReq, Method reqMethod) throws IOException
    {
        if (solrRsp.getException() != null)
        {
            sendError((HttpServletResponse) response, solrRsp.getException());
        }
        else
        {
            // Now write it out
            response.setContentType(responseWriter.getContentType(solrReq, solrRsp));
            if (Method.HEAD != reqMethod)
            {
                if (responseWriter instanceof BinaryQueryResponseWriter)
                {
                    BinaryQueryResponseWriter binWriter = (BinaryQueryResponseWriter) responseWriter;
                    binWriter.write(response.getOutputStream(), solrReq, solrRsp);
                }
                else
                {
                    PrintWriter out = response.getWriter();
                    responseWriter.write(out, solrReq, solrRsp);

                }
            }
            // else http HEAD request, nothing to write out, waited this long
            // just to get ContentType
        }
    }

}
