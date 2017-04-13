import BIT.highBIT.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class ICount {
    private static PrintStream out = null;
    private static long intersections = 0;
    private static long successfulIntersections = 0;
    private static long traces = 0;
    private static String template = "";


    private final static String TEMPLATE_INIT_MAIN = "==============================\n" +
            "=====INSTRUMENTATION INIT=====\n" +
            "==============================\n";
    private final static String TEMPLATE_RAYTRACER_CALL = "==============================\n" +
            "========RAYTRACER INIT========\n" +
            "==============================\n";
    private final static String TEMPLATE_RAYTRACER_CALL_END = "==============================\n" +
            "========RAYTRACER END=========\n" +
            "==============================\n";
    private static int counter = 0;

    /*
     * main reads in all the files class files present in the input directory,
     * instruments them, and outputs them to the specified output directory.
     */
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
                            routine.addBefore("ICount", "incIntersections", "Nothing");
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
                // see java.util.Enumeration for more information on Enumeration
                // class
                ci.addBefore("ICount", "addTemplateInitMain", "Main");
                for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements(); ) {
                    Routine routine = (Routine) e.nextElement();
                    //routine.addBefore("ICount", "methodIn", routine.getMethodName());
                    //routine.addAfter("ICount", "methodOut", routine.getMethodName());
                    if (routine.getMethodName().startsWith("render")) {
                        routine.addBefore("ICount", "nbThreads", routine.getMethodName());
                        routine.addBefore("ICount", "writeStart", "Nothing");
                        routine.addAfter("ICount", "nbThreads", routine.getMethodName());
                        routine.addAfter("ICount", "writeMetrics", "Nothing");
                        routine.addAfter("ICount", "writeToFile", "Main");
                    } else if (routine.getMethodName().startsWith("shade")) {
                        routine.addBefore("ICount", "incSuccessfulIntersections", "Nothing");
                    } else if (routine.getMethodName().startsWith("trace")) {
                        routine.addBefore("ICount", "incTraces", "Nothing");
                    }
                }
                ci.write(argv[1] + System.getProperty("file.separator") + infilename);
            }

            if (infilename.startsWith("RayTracer.class")) {
                // create class info object
                ClassInfo ci = new ClassInfo(argv[0] + System.getProperty("file.separator") + infilename);

                // loop through all the routines
                // see java.util.Enumeration for more information on Enumeration class
                for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements(); ) {
                    Routine routine = (Routine) e.nextElement();
                    routine.addBefore("ICount", "methodCount", routine.getMethodName());
                    if (routine.getMethodName().startsWith("<init>")) {
                        routine.addAfter("ICount", "getClassArgs", "ola");
                    }
                    if (routine.getMethodName().startsWith("draw")) {
                        routine.addAfter("ICount", "classCount", ci.getClassName());
                        routine.addAfter("ICount", "writeToFile", "RayTracer");
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

        String toWrite = "There is " + (count - 4) + " online and " + waiting + " are idle\n";
        try {
            Files.write(Paths.get("metadata.in"), toWrite.getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public static synchronized void incIntersections(String methodName) {
        intersections++;
    }

    public static synchronized void incSuccessfulIntersections(String methodName) {
        successfulIntersections++;
    }

    public static synchronized void incTraces(String methodName) {
        traces++;
    }

    public static synchronized void writeStart(String s) {
        try {
            String toWrite = Thread.currentThread().getId() + "-Started\n";
            Files.write(Paths.get("metadata.in"), toWrite.getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void writeMetrics(String s) {
        try {
            Long i = new Long(intersections);
            Long si = new Long(successfulIntersections);
            Long t = new Long(traces);
            Double successFactor = new Double(si * 100.0 / i);
            String toWrite = "\tintersections=" + i.toString() + "\n" +
                    "\tsuccessfulIntersections=" + si.toString() + "\n" +
                    "\tsuccessFactor=" + successFactor + "\n" +
                    "\ttraces=" + t.toString() + "\n" +
                    Thread.currentThread().getId() + "-Ended\n";
            Files.write(Paths.get("metadata.in"), toWrite.getBytes(), StandardOpenOption.APPEND);
            intersections = 0;
            successfulIntersections = 0;
            traces = 0;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void methodIn(String methodName) {
        template += "Entrei no metodo " + methodName + " Sou a thread " + Thread.currentThread().getId() + "\n";
    }

    public static synchronized void methodOut(String methodName) {
        template += "Sai do metodo " + methodName + " Sou a thread " + Thread.currentThread().getId() + "\n";
    }

    public static synchronized void methodCount(String methodName) {
        counter += 1;
    }

    public static synchronized void classCount(String methodName) {
        template += TEMPLATE_RAYTRACER_CALL;
        template += "For class " + methodName + " in thread " + Thread.currentThread().getId()
                + " there were " + counter + " methods run.\n";
        counter = 0;
        template += TEMPLATE_RAYTRACER_CALL_END;
    }

    public static synchronized void addTemplateInitMain(String methodName) {
        template += TEMPLATE_INIT_MAIN;
    }

    public static synchronized void getClassArgs(String methodName) {
        template += "============INIT================\n";
    }

    public static synchronized void writeToFile(String ola) {
        try {
            Files.write(Paths.get("metadata.in"), template.getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

}