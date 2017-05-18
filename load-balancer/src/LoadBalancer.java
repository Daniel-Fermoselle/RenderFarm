import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
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
import java.net.URL;
import java.util.*;
import java.util.concurrent.Executors;

public class LoadBalancer {

    private static final long INSTANCE_UPDATE_RATE = 10 * 1000;
    private static final int TIME_CONVERSION = 45;
    private static final String TABLE_NAME_COUNT = "CountMetricStorageSystem";
    private static final String TABLE_NAME_SUCCESS = "SuccessFactorStorageSystem";
    public static final String INSTANCES_AMI_ID = "ami-1d9b4272";
    private static final int NB_MAX_THREADS = 5;

    private static AmazonDynamoDB dynamoDB;
    private static AmazonEC2 ec2;
    private static AmazonCloudWatch cloudWatch;


    private static AutoScaler as;

    private static List<Instance> instances;

    private static HashMap<Instance, Integer> instanceActiveThreads;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(80), 0);
        init();
        initDatabase();
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

        cloudWatch = AmazonCloudWatchClientBuilder.standard().withRegion("eu-central-1")
                .withCredentials(new AWSStaticCredentialsProvider(credentials)).build();

        setInstanceActiveThreads(new HashMap<Instance, Integer>());

        as = new AutoScaler(ec2, dynamoDB, cloudWatch);

        getInstances();

        // create thread to from time to time to update instances
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                System.out.println("Going to update the instances that I know");
                try {
                    as.updateInstances();
                    getInstances();
                } catch (IOException e) {
                    throw new RuntimeException(e.getMessage());
                }
            }
        }, INSTANCE_UPDATE_RATE, INSTANCE_UPDATE_RATE);

    }

    private static void initDatabase() throws Exception {
        try {
            // Create a table with a primary hash key named 'ip', which holds
            // a string
            CreateTableRequest createTableRequest = new CreateTableRequest().withTableName(TABLE_NAME_SUCCESS)
                    .withKeySchema(new KeySchemaElement().withAttributeName("filename").withKeyType(KeyType.HASH))
                    .withAttributeDefinitions(
                            new AttributeDefinition().withAttributeName("filename").withAttributeType(ScalarAttributeType.S))
                    .withProvisionedThroughput(
                            new ProvisionedThroughput().withReadCapacityUnits(1L).withWriteCapacityUnits(1L));

            // Create table if it does not exist yet
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
            TableUtils.waitUntilActive(dynamoDB, TABLE_NAME_SUCCESS);

            // wait for the table to move into ACTIVE state
            TableUtils.waitUntilActive(dynamoDB, TABLE_NAME_COUNT);

            // Describe our new table
            DescribeTableRequest describeTableRequest = new DescribeTableRequest().withTableName(TABLE_NAME_SUCCESS);
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
                boolean inInstanceActiveThreads = getInstanceActiveThreads().keySet().contains(instance);
                if(test) {
                    System.out.println("Instance " + instance.getPublicIpAddress() + " passed the test");
                    instances.add(instance);
                    if (!inInstanceActiveThreads){
                        putInstanceActiveThreads(instance, NB_MAX_THREADS);
                    }
                }
                else {
                    System.out.println("Instance " + instance.getPublicIpAddress() + " failed the test");
                    if (!inInstanceActiveThreads) {
                        removeInstanceActiveThreads(instance);
                    }
                }
            }
        }

        Object[] instancesActiveThreads = getInstanceActiveThreads().keySet().toArray();

        for (Object instance : instancesActiveThreads) {
            if (!instances.contains((Instance) instance)){
                removeInstanceActiveThreads((Instance) instance);
            }
        }

        System.out.println("------------------");
        for (Instance instance : getInstanceActiveThreads().keySet()) {
            System.out.println(instance.getPrivateIpAddress() + " -- " + getInstanceActiveThreads(instance));
        }
        System.out.println("------------------");

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

    public static String getFreeInstance() {
        for (Instance instance : getInstanceActiveThreads().keySet()) {
            if (getInstanceActiveThreads(instance) == NB_MAX_THREADS){
                return instance.getInstanceId();
            }
        }
        throw new RuntimeException("No idle instances");
    }

    public static synchronized HashMap<Instance, Integer> getInstanceActiveThreads() {

        return instanceActiveThreads;
    }

    public static synchronized void setInstanceActiveThreads(HashMap<Instance, Integer> instanceActiveThreads) {
        LoadBalancer.instanceActiveThreads = instanceActiveThreads;
    }

    public static synchronized void putInstanceActiveThreads(Instance i, Integer nbThreads){
        LoadBalancer.instanceActiveThreads.put(i, nbThreads);
    }

    public static synchronized void removeInstanceActiveThreads(Instance i){
        LoadBalancer.instanceActiveThreads.remove(i);
    }

    public static synchronized Integer getInstanceActiveThreads(Instance i){
        return LoadBalancer.instanceActiveThreads.get(i);
    }

    public static synchronized void incInstanceActiveThreads(Instance i){
        int nbThreads = LoadBalancer.instanceActiveThreads.get(i);
        if(nbThreads < NB_MAX_THREADS) {
            LoadBalancer.instanceActiveThreads.put(i,  nbThreads + 1);
        }
        else {
            throw new RuntimeException("incInstanceActiveThreads when number of maximum threads reached");
        }
    }

    public static synchronized void decInstanceActiveThreads(Instance i){
        int nbThreads = LoadBalancer.instanceActiveThreads.get(i);
        if(nbThreads > 0) {
            LoadBalancer.instanceActiveThreads.put(i,  nbThreads - 1);
        }
        else {
            throw new RuntimeException("decInstanceActiveThreads when number of threads is 0");
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
            Instance i = getInstanceWithMaxThreads();
            String instanceIp = i.getPublicIpAddress();
            decInstanceActiveThreads(i);

            // Forward the request
            String query = t.getRequestURI().getQuery();
            int timeout = getRightTimeout(query, getInstanceActiveThreads(i) + "");

            try {
                redirect(t, instanceIp, query, timeout);
                incInstanceActiveThreads(i);
            } catch (java.net.SocketTimeoutException e) {
                System.out.println("TimeoutException");
                try {
                    if (testInstance(i)) {
                        System.out.println("Instance alive going to double timeout");
                        redirect(t, instanceIp, query, timeout * 2);
                        incInstanceActiveThreads(i);
                    } else {
                        throw new IOException();
                    }
                } catch (IOException ex) {
                    System.out.println("Instance " + i.getPublicIpAddress() + " should be dead going to remove it and " +
                            "redirect request");
                    instances.remove(i);
                    removeInstanceActiveThreads(i);
                    handle(t);
                }
            } catch (IOException e) {
                System.out.println("Instance " + i.getPublicIpAddress() + "should be dead going to remove it, got IO");
                instances.remove(i);
                removeInstanceActiveThreads(i);
                handle(t);
            }
        }

        private void redirect(HttpExchange t, String instanceIp, String query, int timeout)
                throws IOException {
            URL url = new URL("http://" + instanceIp + ":8000/r.html" + "?" + query);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            System.out.println("Timeout value: " + timeout);
            if (timeout != -1) {
                conn.setConnectTimeout(timeout);//ir buscar metricas
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

        private Instance getInstanceWithMaxThreads(){
            int nbThreads = 0;
            Instance instanceToReturn = null;
            for (Instance instance : getInstanceActiveThreads().keySet()) {
                int nbThreadsInMap = getInstanceActiveThreads(instance);

                if(nbThreadsInMap >= nbThreads && instances.contains(instance)){
                    instanceToReturn = instance;
                    nbThreads = nbThreadsInMap;
                }

                if(!instances.contains(instance)){
                  removeInstanceActiveThreads(instance);
                }
            }

            return instanceToReturn;
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

