import BIT.highBIT.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class RayTracerInstrument {
    private static long[] intersections = {0,0,0,0,0};
    private static long[] successfulIntersections = {0,0,0,0,0};
    private static long[] traces = {0,0,0,0,0};
    private static int[] counter = {0,0,0,0,0};
    private static int NON_RELEVANT_THREADS = 3;

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
                        routine.addBefore("RayTracerInstrument", "nbThreads", routine.getMethodName());
                        routine.addBefore("RayTracerInstrument", "writeStart", "Nothing");
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

                // loop through all the routines
                for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements(); ) {
                    Routine routine = (Routine) e.nextElement();
                    routine.addBefore("RayTracerInstrument", "methodCount", routine.getMethodName());
                    if (routine.getMethodName().startsWith("draw")) {
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

        String toWrite = "There is " + (count - NON_RELEVANT_THREADS) + " online and " + waiting + " are idle\n";
        try {
            Files.write(Paths.get("metadata.in"), toWrite.getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public static synchronized void incIntersections(String methodName) {
    	String[] poolName = Thread.currentThread().getName().split("-");
    	String stringId = poolName[3];
    	long id = new Long(stringId);
    	intersections[(int) id]+=1;
    }

    public static synchronized void incSuccessfulIntersections(String methodName) {
    	String[] poolName = Thread.currentThread().getName().split("-");
    	String stringId = poolName[3];
    	long id = new Long(stringId);
        successfulIntersections[(int) id]+=1;
    }

    public static synchronized void incTraces(String methodName) {
    	String[] poolName = Thread.currentThread().getName().split("-");
    	String stringId = poolName[3];
    	long id = new Long(stringId);
        traces[(int) id]+=1;
    }

    public static synchronized void writeStart(String s) {
        try {
            String toWrite = Thread.currentThread().getId() + "-Started\n";
            File f = new File("metadata.in");
            if(f.exists() && !f.isDirectory()) {
                Files.write(Paths.get("metadata.in"), toWrite.getBytes(), StandardOpenOption.APPEND);
            } else {
                Files.write(Paths.get("metadata.in"), toWrite.getBytes(), StandardOpenOption.CREATE);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void writeMetrics(String s) {
        try {
        	String[] poolName = Thread.currentThread().getName().split("-");
        	String stringId = poolName[3];
        	long id = new Long(stringId);
            Long i = new Long(intersections[(int) id]);
            Long si = new Long(successfulIntersections[(int) id]);
            Long t = new Long(traces[(int) id]);
            Double successFactor = new Double(si * 100.0 / i);
            String toWrite = "\tintersections=" + i.toString() + "\n" +
                    "\tsuccessfulIntersections=" + si.toString() + "\n" +
                    "\tsuccessFactor=" + successFactor + "\n" +
                    "\ttraces=" + t.toString() + "\n" +
                    Thread.currentThread().getId() + "-Ended\n";
            File f = new File("metadata.in");
            if(f.exists() && !f.isDirectory()) {
                Files.write(Paths.get("metadata.in"), toWrite.getBytes(), StandardOpenOption.APPEND);
            } else {
                Files.write(Paths.get("metadata.in"), toWrite.getBytes(), StandardOpenOption.CREATE);
            }
            intersections[(int) id] = 0;
            successfulIntersections[(int) id] = 0;
            traces[(int) id] = 0;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void methodCount(String methodName) {
    	String[] poolName = Thread.currentThread().getName().split("-");
    	String stringId = poolName[3];
    	long id = new Long(stringId);
        counter[(int) id] += 1;
    }

    public static synchronized void writeToFile(String methodName) {
        try {
        	String[] poolName = Thread.currentThread().getName().split("-");
        	String stringId = poolName[3];
        	long id = new Long(stringId);
        	String toWrite = "For class " + methodName + " in thread " + Thread.currentThread().getId()
                    + " there were " + counter[(int) id] + " methods run.\n";
        	counter[(int) id] = 0;
            File f = new File("metadata.in");
            if(f.exists() && !f.isDirectory()) {
                Files.write(Paths.get("metadata.in"), toWrite.getBytes(), StandardOpenOption.APPEND);
            } else {
                Files.write(Paths.get("metadata.in"), toWrite.getBytes(), StandardOpenOption.CREATE);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}