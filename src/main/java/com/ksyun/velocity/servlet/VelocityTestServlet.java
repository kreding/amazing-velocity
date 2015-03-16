package com.ksyun.velocity.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.RuntimeSingleton;

import com.baidu.fis.velocity.util.UnicodeReader;

public class VelocityTestServlet extends HttpServlet {
	
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException{
		doRequest(request, response);
	}
	
	public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException{
		doRequest(request, response);
	}
	
	protected void doRequest(HttpServletRequest request, HttpServletResponse response) throws IOException{
		String path = request.getServletPath();
		try {
            URL url = request.getServletContext().getResource(path);
            if (url != null) {
                String enc = RuntimeSingleton.getString(RuntimeConstants.INPUT_ENCODING);
                BufferedReader in = new BufferedReader(new UnicodeReader(
                        url.openStream(), enc));
                String data = "";
                String inputLine;
                while ((inputLine = in.readLine()) != null){
                    data += inputLine;
                }
                in.close();
                response.addHeader("Content-type", "application/json; charset=utf-8");
                response.getWriter().write(data);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
	}
}
