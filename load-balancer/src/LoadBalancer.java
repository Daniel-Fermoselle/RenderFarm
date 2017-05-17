import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DeleteItemOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.*;
import com.amazonaws.services.dynamodbv2.util.TableUtils;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Executors;

public class LoadBalancer {

    private static final long INSTANCE_UPDATE_RATE = 31 * 1000;
    private static final int TIME_CONVERSION = 45;
    private static final String TABLE_NAME = "MetricStorageSystem";
    private static final String TABLE_NAME_COUNT = "CountMetricStorageSystem";
    private static final String INSTANCES_AMI_ID = "ami-1d9b4272";

    private static AmazonDynamoDB dynamoDB;
    private static AmazonEC2 ec2;

    private static List<Instance> instances;

    private static boolean initializing = true;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(80), 0);
        init();
        initDatabase();
        initializing = false;
        System.out.println(instances.size());
        server.createContext("/test", new MyHandler());
        server.setExecutor(null); // creates a default executor

        server.createContext("/r.html", new RayTracerLBHandler());
        server.setExecutor(Executors.newFixedThreadPool(10)); // creates a default executor

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
                try {
                    getInstances();
                } catch (IOException e) {
                    throw new RuntimeException(e.getMessage());
                }
                cleanOldInstances();
            }
        }, INSTANCE_UPDATE_RATE, INSTANCE_UPDATE_RATE);

    }

    private static void cleanOldInstances() {
        HashMap<String, Condition> scanFilter = new HashMap<String, Condition>();
        Condition condition = new Condition().withComparisonOperator(ComparisonOperator.GE.toString())
                .withAttributeValueList(new AttributeValue().withS("0"));
        scanFilter.put("threads", condition);

        ScanRequest scanRequest = new ScanRequest(TABLE_NAME).withScanFilter(scanFilter);
        ScanResult scanResult = dynamoDB.scan(scanRequest);

        boolean inInstances;
        for (Map<String, AttributeValue> key : scanResult.getItems()){
            String ipInMap = key.get("ip").getS();
            inInstances = false;

            for (Instance instance : instances) {
                if(instance.getPrivateIpAddress().equals(ipInMap)){
                    inInstances =true;
                }
            }

            if(!inInstances){
                deleteIpFromDB(ipInMap);
            }

        }

    }

    private static void deleteIpFromDB(String ipAddr) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("ip", new AttributeValue().withS(ipAddr));

        DeleteItemRequest deleteRequest = new DeleteItemRequest().withTableName(TABLE_NAME).withKey(key);

        DeleteItemResult result = dynamoDB.deleteItem(deleteRequest);
        System.out.println("Result from removing item: " + result);
    }

    private static void initDatabase() throws Exception {
        try {
            // Create a table with a primary hash key named 'ip', which holds
            // a string
            CreateTableRequest createTableRequest = new CreateTableRequest().withTableName(TABLE_NAME)
                    .withKeySchema(new KeySchemaElement().withAttributeName("ip").withKeyType(KeyType.HASH))
                    .withAttributeDefinitions(
                            new AttributeDefinition().withAttributeName("ip").withAttributeType(ScalarAttributeType.S))
                    .withProvisionedThroughput(
                            new ProvisionedThroughput().withReadCapacityUnits(1L).withWriteCapacityUnits(1L));

            DeleteTableRequest deleteTableRequest = new DeleteTableRequest().withTableName(TABLE_NAME);

            // Create table if it does not exist yet
            TableUtils.deleteTableIfExists(dynamoDB, deleteTableRequest);
            TableUtils.createTableIfNotExists(dynamoDB, createTableRequest);

            //------------------------------------//
            //---Create table for count metrics---//
            //------------------------------------//
            createTableRequest = new CreateTableRequest()
                    .withTableName(TABLE_NAME_COUNT)
                    .withKeySchema(new KeySchemaElement().withAttributeName("filename-resolution").withKeyType(KeyType.HASH))
                    .withAttributeDefinitions(
                            new AttributeDefinition().withAttributeName("filename-resolution")
                            .withAttributeType(ScalarAttributeType.S))
                    .withProvisionedThroughput(
                            new ProvisionedThroughput().withReadCapacityUnits(1L).withWriteCapacityUnits(1L));


            // Create table if it does not exist yet
            TableUtils.createTableIfNotExists(dynamoDB, createTableRequest);

            // wait for the table to move into ACTIVE state
            TableUtils.waitUntilActive(dynamoDB, TABLE_NAME);

            // wait for the table to move into ACTIVE state
            TableUtils.waitUntilActive(dynamoDB, TABLE_NAME_COUNT);

            // Describe our new table
            DescribeTableRequest describeTableRequest = new DescribeTableRequest().withTableName(TABLE_NAME);
            TableDescription tableDescription = dynamoDB.describeTable(describeTableRequest).getTable();
            System.out.println("Table Description: " + tableDescription);

            describeTableRequest = new DescribeTableRequest().withTableName(TABLE_NAME_COUNT);
            tableDescription = dynamoDB.describeTable(describeTableRequest).getTable();
            System.out.println("Table Description: " + tableDescription);
            //------------------------------------//
            //---Create table for count metrics---//
            //------------------------------------//

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

    private static void getInstances() throws IOException{
        DescribeInstancesResult describeInstancesRequest = ec2.describeInstances();
        List<Reservation> reservations = describeInstancesRequest.getReservations();
        ArrayList<Instance> tmpInstances = new ArrayList<Instance>();
        instances = new ArrayList<Instance>();

        for (Reservation reservation : reservations) {
            tmpInstances.addAll(reservation.getInstances());
        }

        for (Instance instance : tmpInstances) {
            if (instance.getImageId().equals(INSTANCES_AMI_ID) && instance.getState().getCode() == 16) {
                boolean test = testInstance(instance);
                if(test) {
                    System.out.println("Instance " + instance.getPublicIpAddress() + " passed the test");
                    instances.add(instance);
                }

                if(!test && !initializing) {
                    System.out.println("Instance " + instance.getPublicIpAddress() + " failed the test");
                    deleteIpFromDB(instance.getPrivateIpAddress());
                }
            }
        }
    }

    private static boolean testInstance(Instance instance) throws IOException {
        try {
            String query = "testtest";
            URL url = new URL("http://" + instance.getPublicIpAddress() + ":8000/test" + "?" + query);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                return true;
            }
            return false;
        } catch (java.net.ConnectException e){
            return false;
        }
    }

    private static String getContent(Object content) throws IOException {
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
            String[] ipAndThreads = getIpAndThreadsOfInstance();
            Instance i = getInstanceFromIp(ipAndThreads[0]);
            String instanceIp = i.getPublicIpAddress();

            // Forward the request
            String query = t.getRequestURI().getQuery();
            int timeout = getRightTimeout(query, ipAndThreads[1]);

            try {
                redirect(t, instanceIp, query, timeout, false);
            } catch (java.net.SocketTimeoutException e) {
                System.out.println("TimeoutException");
                try {
                    if (testInstance(i)) {
                        System.out.println("Instance alive going to double timeout");
                        redirect(t, instanceIp, query, timeout * 2, false);
                    } else {
                        throw new IOException();
                    }
                } catch (IOException ex) {
                    System.out.println("Instance " + i.getPublicIpAddress() + " should be dead going to remove it and " +
                            "redirect request");
                    deleteIpFromDB(i.getPrivateIpAddress());
                    instances.remove(i);
                    handle(t);
                }
            } catch (IOException e) {
                System.out.println("Instance " + i.getPublicIpAddress() + "should be dead going to remove it, got IO");
                deleteIpFromDB(i.getPrivateIpAddress());
                instances.remove(i);
                handle(t);
            }
        }

        private void redirect(HttpExchange t, String instanceIp, String query, int timeout, boolean test)
                throws IOException {
            URL url;
            if (test) {
                url = new URL("http://" + instanceIp + ":8000/test" + "?" + query);
            } else {
                url = new URL("http://" + instanceIp + ":8000/r.html" + "?" + query);
            }
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            System.out.println("Timeout value: " + timeout);
            //if (timeout != -1) {
            //    conn.setConnectTimeout(timeout);//ir buscar metricas
            //}

            if (timeout != -1) {
                conn.setConnectTimeout(timeout);//ir buscar metricas

            }
            if (timeout == -1) {
                conn.setConnectTimeout(1);//ir buscar metricas
            }

            conn.setRequestMethod("GET");
            // Get the right information from the request
            t.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            t.sendResponseHeaders(conn.getResponseCode(), conn.getContentLength());
            String content = getContent(conn.getContent());
            OutputStream os = t.getResponseBody();
            os.write(content.getBytes());
            os.close();
        }

        private String[] getIpAndThreadsOfInstance(){
        	HashMap<String, Condition> scanFilter = new HashMap<String, Condition>();
            Condition condition = new Condition().withComparisonOperator(ComparisonOperator.GE.toString())
                    .withAttributeValueList(new AttributeValue().withS("0"));
            scanFilter.put("threads", condition);

            ScanRequest scanRequest = new ScanRequest(TABLE_NAME).withScanFilter(scanFilter);
            ScanResult scanResult = dynamoDB.scan(scanRequest);
            String[] ipAndThreadsQuery = getIpAndThreadsFromQuery(scanResult);
            System.out.println("Result: " + scanResult);
            return ipAndThreadsQuery;
        }
        
        private Instance getInstanceFromIp(String ip) {
            for (Instance instance : instances) {
                System.out.println(instance.getPrivateIpAddress() + " " + ip);
                if (instance.getPrivateIpAddress().equals(ip)) {
                    return instance;
                }
            }
            throw new RuntimeException("Invalid IP");
        }

        private String[] getIpAndThreadsFromQuery(ScanResult scanResult) {
            String nbThreads = "";
            String ip = "";

            for (Map<String, AttributeValue> maps : scanResult.getItems()) {
                String ipInMap = maps.get("ip").getS();
                String nbThreadInMap = maps.get("threads").getS();

                if (nbThreads.equals("")) {
                    nbThreads = nbThreadInMap;
                    ip = ipInMap;
                }

                if (Integer.parseInt(nbThreadInMap) > Integer.parseInt(nbThreads)) {
                    nbThreads = nbThreadInMap;
                    ip = ipInMap;
                }
            }

            return new String[] {ip, nbThreads};
        }

        private int getRightTimeout(String query, String availableThreads) {
            HashMap<String, String> processedQuery = processQuery(query);
            HashMap<String, Condition> scanFilter = new HashMap<String, Condition>();
            long resolution = Long.parseLong(processedQuery.get("wc")) * Long.parseLong(processedQuery.get("wr"));
            Condition condition = new Condition().withComparisonOperator(ComparisonOperator.EQ.toString())
                    .withAttributeValueList(new AttributeValue().withS(processedQuery.get("f") + "-" + resolution + ""));
            scanFilter.put("filename-resolution", condition);

            ScanRequest scanRequest = new ScanRequest(TABLE_NAME_COUNT).withScanFilter(scanFilter);
            ScanResult scanResult = dynamoDB.scan(scanRequest);
            System.out.println("Result: " + scanResult);

            return getCountFromQuery(scanResult, availableThreads);
        }

        private int getCountFromQuery(ScanResult scanResult, String availableThreads) {
        	int threadsAvailable = Integer.parseInt(availableThreads);
            for (Map<String, AttributeValue> maps : scanResult.getItems()) {
                String count = maps.get("count").getS(); //MAYBE WE NEED MORE VERIFICATIONS
                Long i = (Long.parseLong(count) / TIME_CONVERSION) * (6-threadsAvailable); //This const is the relation between the count and time but maybe this is not linear problem
                return i.intValue();
            }
            return -1;
        }

        private HashMap<String, String> processQuery(String q) {
            HashMap<String, String> process = new HashMap<String, String>();
            String[] splitAnds = q.split("&");
            for (String s : splitAnds) {
                String[] pair = s.split("=");
                process.put(pair[0], pair[1]);
            }
            return process;
        }

    }
}

