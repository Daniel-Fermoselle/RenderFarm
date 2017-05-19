import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
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
    public static final int TIME_CONVERSION = 45;
    public static final String TABLE_NAME_COUNT = "CountMetricStorageSystem";
    public static final String TABLE_NAME_SUCCESS = "SuccessFactorStorageSystem";
    public static final String INSTANCES_AMI_ID = "ami-1d9b4272";
    public static final int NB_MAX_THREADS = 5;
    private static final long AS_UPDATE_RATE = 1000 * 70;
    public static final boolean DEBUG = false;

    public static AmazonDynamoDB dynamoDB;
    public static AmazonEC2 ec2;


    private static AutoScaler as;

    private static List<Instance> instances;

    private static HashMap<Instance, Integer> instanceActiveThreads;
    private static HashMap<Instance, ArrayList<String>> runningRequests;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(80), 0);
        init();
        initDatabase();
        server.createContext("/test", new MyHandler());
        server.setExecutor(null); // creates a default executor

        server.createContext("/r.html", new RayTracerLBHandler());
        server.setExecutor(Executors.newCachedThreadPool()); // creates a default executor

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

        setInstanceActiveThreads(new HashMap<Instance, Integer>());
        setRunningRequests(new HashMap<Instance, ArrayList<String>>());


        as = new AutoScaler(ec2, dynamoDB);

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
            }
        }, INSTANCE_UPDATE_RATE, INSTANCE_UPDATE_RATE);

        // create thread to from time to time to update instances
        Timer timer2 = new Timer();
        timer2.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                System.out.println("<><><><><><><><><>");
                System.out.println("Going to update AS");
                as.updateInstances();
                System.out.println("<><><><><><><><><>");

            }
        }, AS_UPDATE_RATE, AS_UPDATE_RATE);

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
                    System.out.println("Instance " + instance.getPrivateIpAddress() + " passed the test");
                    instances.add(instance);
                    if (!inInstanceActiveThreads){
                        putInstanceActiveThreads(instance, NB_MAX_THREADS);
                        putRunningRequests(instance, new ArrayList<String>());
                    }
                }
                else {
                    System.out.println("Instance " + instance.getPrivateIpAddress() + " failed the test");
                    if (!inInstanceActiveThreads) {
                        removeInstanceActiveThreads(instance);
                        removeRunningRequests(instance);
                    }
                }
            }
        }

        Object[] instancesActiveThreads = getInstanceActiveThreads().keySet().toArray();

        for (Object instance : instancesActiveThreads) {
            if (!instances.contains((Instance) instance)){
                removeInstanceActiveThreads((Instance) instance);
                removeRunningRequests((Instance) instance);
            }
        }

        System.out.println("------------------");
        for (Instance instance : getInstanceActiveThreads().keySet()) {
            System.out.println(instance.getPrivateIpAddress() + " -- " + getInstanceActiveThreads(instance));
        }

        for ( Instance instance : runningRequests.keySet()) {
            if (runningRequests.get(instance).size() > 0) {
                System.out.println("For Instance " + instance.getPrivateIpAddress());
                for (String request : runningRequests.get(instance)) {
                    System.out.println("\t Running request " + request);
                }
            }
        }
        System.out.println("------------------");

    }

    public static boolean testInstance(Instance instance) throws IOException {
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

    public static Instance getFreeInstance() {
        for (Instance instance : getInstanceActiveThreads().keySet()) {
            if (getInstanceActiveThreads(instance) == NB_MAX_THREADS){
                return instance;
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

    public static synchronized HashMap<Instance, ArrayList<String>> getRunningRequests() {
        return runningRequests;
    }

    public static synchronized void setRunningRequests(HashMap<Instance, ArrayList<String>> runningRequests) {
        LoadBalancer.runningRequests = runningRequests;
    }

    private static synchronized void putRunningRequests(Instance instance, ArrayList<String> objects) {
        LoadBalancer.runningRequests.put(instance, objects);
    }

    public static synchronized void removeRunningRequests(Instance instance) {
        LoadBalancer.runningRequests.remove(instance);
    }

    public static synchronized ArrayList<String> getRunningRequests(Instance i){
        return LoadBalancer.runningRequests.get(i);
    }

    public static synchronized void addRequestToRunningRequests(Instance i, String query) {
        ArrayList<String> arrayListInMap = LoadBalancer.runningRequests.get(i);
        arrayListInMap.add(query);
        LoadBalancer.runningRequests.put(i, arrayListInMap);
    }

    public static synchronized void deleteRequestFromRunningRequests(Instance i, String query) {
        ArrayList<String> arrayListInMap = LoadBalancer.runningRequests.get(i);
        arrayListInMap.remove(query);
        LoadBalancer.runningRequests.put(i, arrayListInMap);
    }

    public static List<Instance> getInstancesList(){
        return instances;
    }

    public static HashMap<String, String> processQuery(String q) {
        HashMap<String, String> process = new HashMap<String, String>();
        String[] splitAnds = q.split("&");
        for (String s : splitAnds) {
            String[] pair = s.split("=");
            process.put(pair[0], pair[1]);
        }
        return process;
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

}

