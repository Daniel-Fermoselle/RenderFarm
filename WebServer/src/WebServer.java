import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.StringUtils;
import raytracer.Main;

import javax.imageio.ImageIO;

//Hi
public class WebServer {

	private static final String TABLE_NAME = "MetricStorageSystem";
	private static AmazonDynamoDB dynamoDB;

	public static void main(String[] args) throws Exception {
		init();
		HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
		server.createContext("/test", new MyHandler());
		server.setExecutor(null); // creates a default executor

		server.createContext("/r.html", new RayTracerHandler(server));
		server.setExecutor(Executors.newFixedThreadPool(5)); // creates a
																// default
																// executor

		server.start();
	}

	private static void init() throws Exception {

		AWSCredentials credentials = null;
		try {
			credentials = new ProfileCredentialsProvider().getCredentials();
		} catch (Exception e) {
			throw new AmazonClientException("Cannot load the credentials from the credential profiles file. "
					+ "Please make sure that your credentials file is at the correct "
					+ "location (~/.aws/credentials), and is in valid format.", e);
		}
		dynamoDB = AmazonDynamoDBClientBuilder.standard().withRegion("eu-central-1")
				.withCredentials(new AWSStaticCredentialsProvider(credentials)).build();

	}

	static class MyHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange t) throws IOException {
			System.out.println("Got a test  request");
			String response = "This was the query:" + t.getRequestURI().getQuery() + "##";
			// Get the right information from the request
			t.sendResponseHeaders(200, response.length());
			OutputStream os = t.getResponseBody();
			os.write(response.getBytes());
			os.close();
		}
	}

	static class RayTracerHandler implements HttpHandler {

		private HttpServer server;

		public RayTracerHandler(HttpServer server) {
			this.server = server;
		}

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

			long l = Long.parseLong(arguments.get("wc")) * Long.parseLong(arguments.get("wr"));
			createFile(arguments.get("f"), l + "");

			BufferedImage img;
			try {
				img = Main.render(new String[] { "inputs/" + arguments.get("f"), "out.bmp", arguments.get("sc"),
						arguments.get("sr"), arguments.get("wc"), arguments.get("wr"), arguments.get("coff"),
						arguments.get("roff") });
			} catch (InterruptedException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}

			writeToDatabase();

			// Convert BufferedImage to Byte array
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(img, "bmp", baos);
			baos.flush();
			byte[] imageInByte = baos.toByteArray();
			baos.close();

			StringBuilder sb = new StringBuilder();
			sb.append("data:image/bmp;base64,");
			sb.append(StringUtils.newStringUtf8(Base64.encodeBase64(imageInByte, false)));
			String newImage = sb.toString();

			// Get the right information from the request
			t.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");

			t.sendResponseHeaders(200, ("<img src=" + newImage + " />").getBytes().length);
			OutputStream os = t.getResponseBody();
			os.write(("<img src=" + newImage + " />").getBytes());
			os.close();
		}

		private void writeToDatabase() throws IOException {
			System.out.println(
					new String(Files.readAllBytes(Paths.get("metrics-" + Thread.currentThread().getName() + ".out"))));
			HashMap<String,String> fileMetrics = readFile();
			Map<String, AttributeValue> item = newItem(server.getAddress().getHostName(), fileMetrics.get("threads"));
			PutItemRequest putItemRequest = new PutItemRequest(TABLE_NAME, item);
			PutItemResult putItemResult = dynamoDB.putItem(putItemRequest);
			System.out.println("Result: " + putItemResult);

		}

		private static Map<String, AttributeValue> newItem(String ip, String threads) {
			Map<String, AttributeValue> item = new HashMap<String, AttributeValue>();
			item.put("ip", new AttributeValue(ip));
			item.put("threads", new AttributeValue(threads));
			return item;
		}

		private void createFile(String f, String resolution) throws IOException {
			Files.deleteIfExists(Paths.get("metrics-" + Thread.currentThread().getName() + ".out"));
			String toWrite = "filename=" + f + "\n" + "resolution=" + resolution + "\n";
			Files.write(Paths.get("metrics-" + Thread.currentThread().getName() + ".out"), toWrite.getBytes(),
					StandardOpenOption.CREATE_NEW);
		}

		private HashMap<String, String> readFile() throws IOException {
			String fileString = new String(Files.readAllBytes(Paths.get("metrics-" + Thread.currentThread().getName() + ".out")),StandardCharsets.UTF_8);
			HashMap<String, String> m = new HashMap<String, String>();
			String[] serverData = fileString.split("\n");

			for (String p : serverData) {
				String[] pair = p.split("=");
				m.put(pair[0], pair[1]);
			}

			return m;
		}
	}
}
