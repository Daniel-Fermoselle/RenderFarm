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

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.StringUtils;
import raytracer.Main;

import javax.imageio.ImageIO;

//Hi
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


            //Convert BufferedImage to Byte array
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write( img, "bmp", baos );
            baos.flush();
            byte[] imageInByte = baos.toByteArray();
            baos.close();


            StringBuilder sb = new StringBuilder();
            sb.append("data:image/png;base64,");
            sb.append(StringUtils.newStringUtf8(Base64.encodeBase64(imageInByte, false)));
            String newImage = sb.toString();


            //Get the right information from the request
            t.getResponseHeaders().set("Content-Type", "image/bmp");

            t.sendResponseHeaders(200, ("<img src="+newImage+" />").getBytes().length);
            OutputStream os = t.getResponseBody();
            os.write(("<img src="+newImage+" />").getBytes());
            os.close();
        }
    }
}
