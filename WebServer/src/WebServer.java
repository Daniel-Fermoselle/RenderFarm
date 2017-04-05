import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import raytracer.

public class WebServer {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/test", new MyHandler());
        server.setExecutor(null); // creates a default executor

        //TODO Set the right context
        server.createContext("/r", new RayTracerHandler());
        server.setExecutor(Executors.newFixedThreadPool(5)); // creates a default executor
        server.start();
    }

    static class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String response = "This was the query:" + t.getRequestURI().getQuery() + "##";
            //Get the right information from the request
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    static class RayTracerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String query = t.getRequestURI().getQuery();
            String[] splitQuery = query.split("&");
            HashMap<String, String> arguments = new HashMap<>();

            for (String split : splitQuery) {
                String[] pair = split.split("=");
                arguments.put(pair[0], pair[1]);
            }


            String response = "This was the query:" + t.getRequestURI().getQuery() + "##";
            //Get the right information from the request
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

}
