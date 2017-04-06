import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import raytracer.*;

import javax.imageio.ImageIO;

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
            System.out.println("Got a test  request");
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
            System.out.println("Got a raytracer request");
            String query = t.getRequestURI().getQuery();
            String[] splitQuery = query.split("&");
            HashMap<String, String> arguments = new HashMap<>();

            for (String split : splitQuery) {
                String[] pair = split.split("=");
                arguments.put(pair[0], pair[1]);
            }

            BufferedImage img;
            try {
                img = Main.render(new String[]{"inputs/"+arguments.get("f"), "out.bmp",
                        arguments.get("sc"), arguments.get("sr"),
                        arguments.get("wc"), arguments.get("wr"),
                        arguments.get("coff"), arguments.get("roff")});
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write( img, "bmp", baos );
            baos.flush();
            byte[] imageInByte = baos.toByteArray();
            baos.close();


            //Get the right information from the request
            t.getResponseHeaders().set("Content-Type", "image/jpeg");

            t.sendResponseHeaders(200, imageInByte.length);
            OutputStream os = t.getResponseBody();
            os.write(imageInByte);
            os.close();
        }
    }
}
