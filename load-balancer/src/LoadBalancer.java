import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.util.TableUtils;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient;
import com.amazonaws.services.elasticloadbalancing.model.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Executors;

public class LoadBalancer {

	private static final long INSTANCE_UPDATE_RATE = 2 * 60 * 1000;
	private static final String TABLE_NAME = "MetricStorageSystem";

	private static AmazonDynamoDBClient dynamoDB;
	private static AmazonEC2 ec2;

	private static List<Instance> instances;

	public static void main(String[] args) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress(80), 0);
		init();
		System.out.println(instances.size());
		server.createContext("/test", new MyHandler());
		server.setExecutor(null); // creates a default executor

		server.createContext("/r.html", new RayTracerLBHandler());
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
		ec2 = AmazonEC2ClientBuilder.standard().withRegion("eu-central-1")
				.withCredentials(new AWSStaticCredentialsProvider(credentials)).build();
		dynamoDB = AmazonDynamoDBClientBuilder.standard().withRegion("eu-central-1")
				.withCredentials(new AWSStaticCredentialsProvider(credentials)).build();

		getInstances();

		// create thread to from time to time to update instances
		Timer timer = new Timer();
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				System.out.println("Going to update the instances that I know");
				getInstances();
			}
		}, INSTANCE_UPDATE_RATE, INSTANCE_UPDATE_RATE);

	}

	private static void initDatabase() throws Exception {
		try {
			// Create a table with a primary hash key named 'name', which holds
			// a string
			CreateTableRequest createTableRequest = new CreateTableRequest().withTableName(TABLE_NAME)
					.withKeySchema(new KeySchemaElement().withAttributeName("ip").withKeyType(KeyType.HASH),
							new KeySchemaElement().withAttributeName("threads").withKeyType(KeyType.RANGE))
					.withAttributeDefinitions(
							new AttributeDefinition().withAttributeName("ip").withAttributeType(ScalarAttributeType.S),
							new AttributeDefinition().withAttributeName("threads")
									.withAttributeType(ScalarAttributeType.N))
					.withProvisionedThroughput(
							new ProvisionedThroughput().withReadCapacityUnits(1L).withWriteCapacityUnits(1L));

			// Create table if it does not exist yet
			TableUtils.deleteTableIfExists(dynamoDB, createTableRequest);
			TableUtils.createTableIfNotExists(dynamoDB, createTableRequest);

			// wait for the table to move into ACTIVE state
			TableUtils.waitUntilActive(dynamoDB, tableName);

			// Describe our new table
			DescribeTableRequest describeTableRequest = new DescribeTableRequest().withTableName(tableName);
			TableDescription tableDescription = dynamoDB.describeTable(describeTableRequest).getTable();
			System.out.println("Table Description: " + tableDescription);

			// Add an item
			Map<String, AttributeValue> item = newItem("Bill & Ted's Excellent Adventure", 1989, "****", "James",
					"Sara");
			PutItemRequest putItemRequest = new PutItemRequest(tableName, item);
			PutItemResult putItemResult = dynamoDB.putItem(putItemRequest);
			System.out.println("Result: " + putItemResult);

			// Add another item
			item = newItem("Airplane", 1980, "*****", "James", "Billy Bob");
			putItemRequest = new PutItemRequest(tableName, item);
			putItemResult = dynamoDB.putItem(putItemRequest);
			System.out.println("Result: " + putItemResult);

			// Scan items for movies with a year attribute greater than 1985
			HashMap<String, Condition> scanFilter = new HashMap<String, Condition>();
			Condition condition = new Condition().withComparisonOperator(ComparisonOperator.GT.toString())
					.withAttributeValueList(new AttributeValue().withN("1985"));
			scanFilter.put("year", condition);
			ScanRequest scanRequest = new ScanRequest(tableName).withScanFilter(scanFilter);
			ScanResult scanResult = dynamoDB.scan(scanRequest);
			System.out.println("Result: " + scanResult);

		} catch (AmazonServiceException ase) {
			System.out.println("Caught an AmazonServiceException, which means your request made it "
					+ "to AWS, but was rejected with an error response for some reason.");
			System.out.println("Error Message:    " + ase.getMessage());
			System.out.println("HTTP Status Code: " + ase.getStatusCode());
			System.out.println("AWS Error Code:   " + ase.getErrorCode());
			System.out.println("Error Type:       " + ase.getErrorType());
			System.out.println("Request ID:       " + ase.getRequestId());
		} catch (AmazonClientException ace) {
			System.out.println("Caught an AmazonClientException, which means the client encountered "
					+ "a serious internal problem while trying to communicate with AWS, "
					+ "such as not being able to access the network.");
			System.out.println("Error Message: " + ace.getMessage());
		}

	}

	private static void getInstances() {
		DescribeInstancesResult describeInstancesRequest = ec2.describeInstances();
		List<Reservation> reservations = describeInstancesRequest.getReservations();
		ArrayList<Instance> tmpInstances = new ArrayList<Instance>();
		instances = new ArrayList<Instance>();

		for (Reservation reservation : reservations) {
			tmpInstances.addAll(reservation.getInstances());
		}

		for (Instance instance : tmpInstances) {
			if (instance.getImageId().equals("ami-d2d103bd") && instance.getState().getCode() == 16) {
				instances.add(instance);
			}
		}
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

	static class RayTracerLBHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange t) throws IOException {
			System.out.println("Got a raytracer request");
			Instance i = getRightInstance();
			String instanceIp = i.getPublicIpAddress();

			// Forward the request
			String query = t.getRequestURI().getQuery();
			URL url = new URL("http://" + instanceIp + ":8000/r.html" + "?" + query);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");

			// Get the right information from the request
			t.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
			t.sendResponseHeaders(conn.getResponseCode(), conn.getContentLength());
			String content = getContent(conn.getContent());
			OutputStream os = t.getResponseBody();
			os.write(content.getBytes());
			os.close();
		}

		private String getContent(Object content) throws IOException {
			InputStreamReader in = new InputStreamReader((InputStream) content);
			BufferedReader buff = new BufferedReader(in);
			String line = "";
			StringBuffer text = new StringBuffer("");
			do {
				text.append(line);
				line = buff.readLine();
			} while (line != null);
			System.out.println(text.toString().length());
			return text.toString();
		}

		private Instance getRightInstance() {
			// Get right instance
			return instances.get(0);
		}
	}
}
