import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
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

    private static final String LOAD_BALANCER_NAME = "RenderFarmLB";

    private static AmazonEC2 ec2;

    private static AmazonElasticLoadBalancing elb;

    private static List<Instance> instances;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(80), 0);
        init();
        server.createContext("/test", new MyHandler());
        server.setExecutor(null); // creates a default executor

        server.createContext("/r.html", new RayTracerLBHandler());
        server.setExecutor(Executors.newFixedThreadPool(5)); // creates a default executor
        server.start();
    }

    /**
     * The only information needed to create a client are security credentials
     * consisting of the AWS Access Key ID and Secret Access Key. All other
     * configuration, such as the service endpoints, are performed
     * automatically. Client parameters, such as proxies, can be specified in an
     * optional ClientConfiguration object when constructing a client.
     *
     * @see com.amazonaws.auth.BasicAWSCredentials
     * @see com.amazonaws.auth.PropertiesCredentials
     * @see com.amazonaws.ClientConfiguration
     */
    private static void init() throws Exception {

        /*
         * The ProfileCredentialsProvider will return your [default]
         * credential profile by reading from the credentials file located at
         * (~/.aws/credentials).
         */
        AWSCredentials credentials = null;
        try {
            credentials = new ProfileCredentialsProvider().getCredentials();
        } catch (Exception e) {
            throw new AmazonClientException(
                    "Cannot load the credentials from the credential profiles file. " +
                            "Please make sure that your credentials file is at the correct " +
                            "location (~/.aws/credentials), and is in valid format.",
                    e);
        }
        ec2 = AmazonEC2ClientBuilder.standard().withRegion("eu-central-1").withCredentials(
                new AWSStaticCredentialsProvider(credentials)).build();
        elb = AmazonElasticLoadBalancingClient.builder().withRegion("eu-central-1").withCredentials(
                new AWSStaticCredentialsProvider(credentials)).build();

        getInstances();

        //create thread to from time to time update instances

        //create thread to see if it's needed to instancite more instances
    }

    private static void getInstances() {
        DescribeInstancesResult describeInstancesRequest = ec2.describeInstances();
        List<Reservation> reservations = describeInstancesRequest.getReservations();
        instances = new ArrayList<Instance>();

        for (Reservation reservation : reservations) {
            instances.addAll(reservation.getInstances());
        }
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

    static class RayTracerLBHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            System.out.println("Got a raytracer request");
            Instance i = getRightInstance();
            String instanceIp = i.getPublicIpAddress();

            //Forward the request
            String query = t.getRequestURI().getQuery();
            URL url = new URL("http://" + instanceIp + ":8000/r.html" + "?" + query);
            HttpURLConnection conn = (HttpURLConnection)url.openConnection();
            conn.setRequestMethod("GET");

            //Get the right information from the request
            t.sendResponseHeaders(conn.getResponseCode(), conn.getContentLength());
            String content = getContent(conn.getContent());
            OutputStream os = t.getResponseBody();
            os.write(content.getBytes());
            os.close();
        }

        private String getContent(Object content) throws IOException {
            InputStreamReader in = new InputStreamReader((InputStream) content);
            BufferedReader buff = new BufferedReader(in);
            String line;
            StringBuffer text = new StringBuffer();
            do {
                line = buff.readLine();
                text.append(line + "\n");
            } while (line != null);
            return text.toString();
        }

        private Instance getRightInstance() {
            //Get right instance
            return instances.get(0);
        }
    }
}
