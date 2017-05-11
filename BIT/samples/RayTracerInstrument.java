import BIT.highBIT.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class RayTracerInstrument {
	//---------Arrays to save the data for each one of the 5 threads--------//
    private static long[] intersections = {0, 0, 0, 0, 0};
    private static long[] successfulIntersections = {0, 0, 0, 0, 0};
    private static long[] traces = {0, 0, 0, 0, 0};
    private static int[] counter = {0, 0, 0, 0, 0};
    //---------Arrays to save the data for each one of the 5 threads--------//
    private static int THREAD_NAME_SPLIT_ID = 3;

    public static void main(String argv[]) {

        String shapesInputPath = argv[0] + System.getProperty("file.separator") + "shapes";
        String shapesOutputPath = argv[1] + System.getProperty("file.separator") + "shapes";
        File file_in = new File(shapesInputPath);
        String infilenames[] = file_in.list();

        for (int i = 0; i < infilenames.length; i++) {
            String infilename = infilenames[i];
            if (infilename.endsWith(".class")) {
                // create class info object
                ClassInfo ci = new ClassInfo(shapesInputPath + System.getProperty("file.separator") + infilename);
                // loop through all the routines
                if ("raytracer/shapes/Shape".equals(ci.getSuperClassName())) {
                    for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements(); ) {
                        Routine routine = (Routine) e.nextElement();
                        if (routine.getMethodName().startsWith("intersect")) {
                            routine.addBefore("RayTracerInstrument", "incIntersections", "Nothing");
                        }
                    }
                }
                ci.write(shapesOutputPath + System.getProperty("file.separator") + infilename);
            }
        }

        file_in = new File(argv[0]);
        infilenames = file_in.list();

        for (int i = 0; i < infilenames.length; i++) {
            String infilename = infilenames[i];
            if (infilename.endsWith("Main.class") || infilename.endsWith("RayTracer.class")) {
                ClassInfo ci = new ClassInfo(argv[0] + System.getProperty("file.separator") + infilename);
                // loop through all the routines
                for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements(); ) {
                    Routine routine = (Routine) e.nextElement();
                    if (routine.getMethodName().startsWith("render")) {
                        routine.addAfter("RayTracerInstrument", "nbThreads", routine.getMethodName());
                        routine.addAfter("RayTracerInstrument", "writeMetrics", "Nothing");
                    } else if (routine.getMethodName().startsWith("shade")) {
                        routine.addBefore("RayTracerInstrument", "incSuccessfulIntersections", "Nothing");
                    } else if (routine.getMethodName().startsWith("trace")) {
                        routine.addBefore("RayTracerInstrument", "incTraces", "Nothing");
                    }
                }
                ci.write(argv[1] + System.getProperty("file.separator") + infilename);
            }

            if (infilename.startsWith("RayTracer.class")) {
                // create class info object
                ClassInfo ci = new ClassInfo(argv[0] + System.getProperty("file.separator") + infilename);

                //loop through all the routines and for each one of them the counter of methods is incremented
                //i.e this part of the code will count the number of method calls that were done while rendering one request
                for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements(); ) {
                    Routine routine = (Routine) e.nextElement();
                    routine.addBefore("RayTracerInstrument", "methodCount", routine.getMethodName());
                    if (routine.getMethodName().startsWith("draw")) {
                    	//As the draw method is the last one called by the raytracer is in this method that all the information
                    	//gathered will be written into the file
                        routine.addAfter("RayTracerInstrument", "writeToFile", ci.getClassName());
                    }
                }


                ci.write(argv[1] + System.getProperty("file.separator") + infilename);
            }
        }
    }

    public static synchronized void nbThreads(String methodName) {
        int count = Thread.activeCount();
        int waiting = 0;
        Thread threads[] = new Thread[count];
        Thread.enumerate(threads);

        for (Thread thread : threads) {
            if (thread.getState().equals(Thread.State.WAITING)) {
                waiting++;
            }
        }

        String toWrite = "threads=" + waiting + "\n";
        try {
            Files.write(Paths.get("metrics-" + Thread.currentThread().getName() + ".out"), toWrite.getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public static synchronized void incIntersections(String methodName) {
    	String[] poolName = Thread.currentThread().getName().split("-");
    	String stringId = poolName[THREAD_NAME_SPLIT_ID];
    	long id = new Long(stringId);
    	intersections[((int) id) - 1]+=1;
    }

    public static synchronized void incSuccessfulIntersections(String methodName) {
    	String[] poolName = Thread.currentThread().getName().split("-");
    	String stringId = poolName[THREAD_NAME_SPLIT_ID];
    	long id = new Long(stringId);
        successfulIntersections[((int) id) - 1]+=1;
    }

    public static synchronized void incTraces(String methodName) {
    	String[] poolName = Thread.currentThread().getName().split("-");
    	String stringId = poolName[THREAD_NAME_SPLIT_ID];
    	long id = new Long(stringId);
        traces[((int) id) - 1]+=1;
    }

    public static synchronized void writeMetrics(String s) {
        try {
        	String[] poolName = Thread.currentThread().getName().split("-");
        	String stringId = poolName[THREAD_NAME_SPLIT_ID];
        	long id = new Long(stringId);
            Long i = new Long(intersections[((int) id) - 1]);
            Long si = new Long(successfulIntersections[((int) id) - 1]);
            Long t = new Long(traces[((int) id) - 1]);
            Double successFactor = new Double(si * 100.0 / i);
            String toWrite = "successFactor=" + successFactor + "\n";
            Files.write(Paths.get("metrics-" + Thread.currentThread().getName() + ".out"), toWrite.getBytes(), StandardOpenOption.APPEND);
            intersections[((int) id) - 1] = 0;
            successfulIntersections[((int) id) - 1] = 0;
            traces[((int) id) - 1] = 0;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //This method as it is stated in the name will count the number of all methods called while drawing 1 request for a thread
    public static synchronized void methodCount(String methodName) {
    	String[] poolName = Thread.currentThread().getName().split("-");
    	String stringId = poolName[THREAD_NAME_SPLIT_ID];
    	long id = new Long(stringId);
        counter[((int) id) - 1] += 1;
    }

    //This method will write the method count information for a thread previously stored in memory to a file
    //If the file doesn't exist it will be crated
    public static synchronized void writeToFile(String methodName) {
        try {
        	String[] poolName = Thread.currentThread().getName().split("-");
        	String stringId = poolName[THREAD_NAME_SPLIT_ID];
        	long id = new Long(stringId);
        	String toWrite = "methodsRun=" + counter[((int) id) - 1] + "\n";
        	counter[((int) id) - 1] = 0;
            Files.write(Paths.get("metrics-" + Thread.currentThread().getName() + ".out"), toWrite.getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}