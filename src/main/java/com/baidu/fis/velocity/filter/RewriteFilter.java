package com.baidu.fis.velocity.filter;

import com.alibaba.fastjson.JSONObject;
import com.baidu.fis.velocity.util.MapJson;
import com.baidu.fis.velocity.util.Settings;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by 2betop on 5/7/14.
 *
 * 负责两件事
 *
 * 1. 读取 server.conf 把设置的转发规则实现
 * 2. 其他转发的实现。如请求时没有加 .vm 后缀，转发到加 .vm 后缀上去。
 *
 */
public class RewriteFilter implements Filter {

    public static class RewriteRulers {

        final public static String DEFAULT_PATH = "/WEB-INF/server.conf";
        final public static String DEFAULT_DIR = "/WEB-INF/";

        protected static class Ruler {
            final public static int TYPE_REWRITE = 0;
            final public static int TYPE_REDIRECT = 1;

            public int type = 0;
            public String pattern;
            public String target;
            public String dest;

            @Override
            public String toString() {
                return "Ruler{" +
                        "type=" + type +
                        ", pattern='" + pattern + '\'' +
                        ", target='" + target + '\'' +
                        ", dest='" + dest + '\'' +
                        '}';
            }
        }

        protected ArrayList<Ruler> rulers = new ArrayList<Ruler>();

        public RewriteRulers() {

        }

        public RewriteRulers(InputStream stream) throws IOException{
            this.load(stream, Charset.forName("UTF-8"));
        }

        public RewriteRulers(InputStream stream, Charset charset) throws IOException{
            this.load(stream, charset);
        }

        public void load(InputStream stream) throws IOException{
            this.load(stream, Charset.forName("UTF-8"));
        }

        public void load(InputStream stream, Charset charset) throws IOException{
            InputStreamReader instream = new InputStreamReader(stream, charset);
            BufferedReader reader = new BufferedReader(instream);

            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // 只识别 rewrite/redirect ，其他的不识别。
                if (line.isEmpty() || !line.startsWith("rewrite") && !line.startsWith("redirect")) {
                    continue;
                }

                String []parts = line.split("\\s+");
                Ruler ruler = new Ruler();

                if (parts[0].toLowerCase().equals("rewrite")) {
                    ruler.type = Ruler.TYPE_REWRITE;
                } else if (parts[0].toLowerCase().equals("redirect")) {
                    ruler.type = Ruler.TYPE_REDIRECT;
                }

                ruler.pattern = parts[1];
                ruler.target = parts[2];

                rulers.add(ruler);
            }

            reader.close();
        }

        public Ruler getRuler(String path) {

            for (Ruler ruler:rulers) {

                if (path.matches(ruler.pattern)) {
                    ruler.dest = path.replaceAll(ruler.pattern, ruler.target);
                    return ruler;
                }
            }

            return null;
        }

        @Override
        public String toString() {
            return "RewriteRulers{" +
                    "rulers=" + rulers +
                    '}';
        }
    }

    private MapJson map = null;

    public void init(FilterConfig config) throws ServletException {
        ServletContext context = config.getServletContext();
        Settings.setApplicationAttribute(ServletContext.class.getName(), context);
        Settings.load(context.getResourceAsStream(Settings.DEFAULT_PATH));
        map = new MapJson();
    }
    public void destroy() {
    }

    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws ServletException, IOException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        // 先执行 rewrite.
        if (handleRewrite(request, response)) {
            return;
        } else if (handlePreview(request, response)) {
            return;
        }

        chain.doFilter(req, resp);
    }


    protected Boolean handlePreview(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException{
        String path = req.getServletPath();
        URL url = req.getServletContext().getResource(path);

        // 找不到资源
        if (url == null && !path.endsWith(".vm")) {

            Pattern reg = Pattern.compile("^/(?:([^/]+)/)?page/(.*)$", Pattern.CASE_INSENSITIVE);
            Matcher matcher = reg.matcher(path);

            if (matcher.find()) {
                String ns = matcher.group(1);
                String file = matcher.group(2);

                JSONObject info = null;

                try {
                    info = ns != null ? map.getNode(ns + ":page/" + file) : map.getNode("page/" + file);
                } catch (Exception ex) {
                    // I don't care!
                }

                // 在 map.json 里面找到了
                if (info!=null) {
                    String resolved = info.getString("uri");

                    // 如果是 vm 文件，servlet 里面会自己找到文件。
                    if (resolved.endsWith(".vm")) {
                        req.getRequestDispatcher(path).forward(req, resp);
                    } else {
                        resolved = Settings.getString("views.path", "/WEB-INF/views") + resolved;
                        req.getRequestDispatcher(resolved).forward(req, resp);
                    }

                    return true;
                } else {
                    // 一般没加 .vm 后缀的路径是找不到的。
                    if (!path.endsWith(".vm")) {
                        path += ".vm";
                    }

                    req.getRequestDispatcher(path).forward(req, resp);
                    return true;
                }
            }
        } else if (path.endsWith(".json")) {
            resp.addHeader("Content-Type", "application/json; charset=utf-8");
        }
        return false;
    }

    // 读取 server.conf 进行转发
    protected Boolean handleRewrite(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException{

        // 避免循环 rewrite
        if (req.getAttribute("origin") != null) {
            return false;
        }

        RewriteRulers parser = new RewriteRulers();

        ArrayList<String> confs = new ArrayList<String>();

        final ArrayList<String> orders = new ArrayList<String>();
        orders.add(RewriteRulers.DEFAULT_DIR + "servercommon.conf");
        orders.add(RewriteRulers.DEFAULT_PATH);

        Set<String> files = req.getServletContext().getResourcePaths(RewriteRulers.DEFAULT_DIR);
        if (files != null) {
            for (String file:files) {
                if (file.endsWith(".conf") && file.contains("/server")) {
                    confs.add(file);
                }
            }
        }

        Collections.sort(confs, new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                return orders.indexOf(o2) - orders.indexOf(o1);
            }
        });

        for(String path:confs) {
            InputStream stream = req.getServletContext().getResourceAsStream(path);

            if (stream!=null) {
                parser.load(stream);
            }
        }

        RewriteRulers.Ruler ruler = parser.getRuler(req.getRequestURI());

        if (ruler!=null) {
            if (ruler.type == RewriteRulers.Ruler.TYPE_REDIRECT) {
                resp.sendRedirect(ruler.dest);
            } else if(ruler.type == RewriteRulers.Ruler.TYPE_REWRITE) {
                req.setAttribute("origin", req.getRequestURI());
                req.getRequestDispatcher(ruler.dest).forward(req, resp);
            }
            return true;
        }

        return false;
    }

}
