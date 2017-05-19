import com.amazonaws.services.dynamodbv2.model.*;
import com.amazonaws.services.ec2.model.Instance;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class RayTracerLBHandler implements HttpHandler {
    //Method that handles a raytracer request
    @Override
    public void handle(HttpExchange t) throws IOException {
        try {
            System.out.println("Got a raytracer request");

            // Forward the request
            String query = t.getRequestURI().getQuery();
            Instance i = null;
            while (i == null){
                try {
                    i = getRightInstance(query);
                }catch (java.util.ConcurrentModificationException e){
                    Thread.sleep(1000);
                }
            }
            String instanceIp = i.getPublicIpAddress();
            System.out.println("Instance chosen to handle the request " + i.getPrivateIpAddress() + " " + query);
            LoadBalancer.decInstanceActiveThreads(i);
            LoadBalancer.addRequestToRunningRequests(i, query);
            int timeout = getRightTimeout(query, LoadBalancer.getInstanceActiveThreads(i) + "");

            try {
                redirect(t, instanceIp, query, timeout);
                LoadBalancer.deleteRequestFromRunningRequests(i, query);
                LoadBalancer.incInstanceActiveThreads(i);
                System.out.println("Instance " + i.getPrivateIpAddress() + " FINISHED REQUEST " + query);
            } catch (java.net.SocketTimeoutException e) {
                System.out.println("TimeoutException");
                try {
                    if (LoadBalancer.testInstance(i)) {
                        System.out.println("Instance alive going to double timeout");
                        redirect(t, instanceIp, query, timeout * 2);
                        System.out.println("Instance " + i.getPrivateIpAddress() + " FINISHED REQUEST " + query);
                        LoadBalancer.deleteRequestFromRunningRequests(i, query);
                        LoadBalancer.incInstanceActiveThreads(i);
                    } else {
                        throw new IOException();
                    }
                } catch (IOException ex) {
                    System.out.println("Instance " + i.getPublicIpAddress() + " should be dead going to remove it and " +
                            "redirect request");
                    LoadBalancer.getInstancesList().remove(i);
                    LoadBalancer.removeInstanceActiveThreads(i);
                    LoadBalancer.removeRunningRequests(i);
                    handle(t);
                }
            } catch (IOException e) {
                System.out.println("Instance " + i.getPublicIpAddress() + " should be dead going to remove it, got IO");
                LoadBalancer.getInstancesList().remove(i);
                LoadBalancer.removeInstanceActiveThreads(i);
                LoadBalancer.removeRunningRequests(i);
                handle(t);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //Method that redirects the request to the instance ip received
    private void redirect(HttpExchange t, String instanceIp, String query, int timeout)
            throws IOException {
        URL url = new URL("http://" + instanceIp + ":8000/r.html" + "?" + query);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        System.out.println("Timeout value: " + timeout);
        if (timeout != -1) {
            conn.setConnectTimeout(timeout);//calculated timeout received
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

    //Method that determines the instance that is going to handle the request
    private synchronized Instance getRightInstance(String request) {
        for (Instance instance : LoadBalancer.getInstancesList()) {
            if (!weight(instance, request)) {
                return instance;
            }
        }

        return null;
    }

    //Gets the right timeout for a request based on its methods count and resolution
    private int getRightTimeout(String query, String availableThreads) {
        HashMap<String, String> processedQuery = LoadBalancer.processQuery(query);
        HashMap<String, Condition> scanFilter = new HashMap<String, Condition>();
        long resolution = Long.parseLong(processedQuery.get("wc")) * Long.parseLong(processedQuery.get("wr"));
        Condition condition = new Condition().withComparisonOperator(ComparisonOperator.EQ.toString())
                .withAttributeValueList(new AttributeValue().withS(processedQuery.get("f") + "-" + resolution + ""));
        scanFilter.put("filename-resolution", condition);

        ScanRequest scanRequest = new ScanRequest(LoadBalancer.TABLE_NAME_COUNT).withScanFilter(scanFilter);
        ScanResult scanResult = LoadBalancer.dynamoDB.scan(scanRequest);

        return getCountFromQuery(scanResult, availableThreads);
    }

    //Converts the number of methods to a estimation timeout
    private int getCountFromQuery(ScanResult scanResult, String availableThreads) {
        int threadsAvailable = Integer.parseInt(availableThreads);
        for (Map<String, AttributeValue> maps : scanResult.getItems()) {
            String count = maps.get("count").getS();
            Long i = (Long.parseLong(count) / LoadBalancer.TIME_CONVERSION) * (6 - threadsAvailable);
            return i.intValue();
        }
        return -1;
    }

    //Gets the content of the response packet and returns it
    private String getContent(Object content) throws IOException {
        InputStreamReader in = new InputStreamReader((InputStream) content);
        BufferedReader buff = new BufferedReader(in);
        String line = "";
        StringBuffer text = new StringBuffer("");
        do {
            text.append(line);
            line = buff.readLine();
        } while (line != null);
        return text.toString();
    }

    //Calculates whether the instance received as the argument is able to handle the request
    private synchronized boolean weight(Instance i, String requestToAdd) {
        if (LoadBalancer.getInstanceActiveThreads(i) == 0) {
            return true;
        }

        ArrayList<String> runningRequests = new ArrayList<>(LoadBalancer.getRunningRequests(i));

        if (runningRequests.size() == 0) {
            return false;
        }

        runningRequests.add(requestToAdd);

        ArrayList<Double> methodCount = new ArrayList<>();
        ArrayList<Double> successFactor = new ArrayList<>();

        for (String runningRequest : runningRequests) {
            HashMap<String, String> request = LoadBalancer.processQuery(runningRequest);

            Double nbMethodCount = AutoScaler.getMethodCounts(request);
            Double nbSuccessFactor = AutoScaler.getSuccessFactor(request);

            if (nbMethodCount == -1.0) {
                nbMethodCount = AutoScaler.estimateNbMethodCount(request);
            }

            if (LoadBalancer.DEBUG) {
                System.out.println("For request " + runningRequest);
                System.out.println("\tnbMethodCount = " + nbMethodCount);
                System.out.println("\tnbSuccessFactor = " + nbSuccessFactor);
            }

            //Query and get methodCount
            methodCount.add(nbMethodCount);

            //Query and get successFactor
            successFactor.add(nbSuccessFactor);
        }

        ArrayList<Integer> integers = Heuristic.calculateRankToSend(methodCount, successFactor);

        if (integers == null) {
            return false;
        }

        return Heuristic.canSendRequest(integers);
    }

}
