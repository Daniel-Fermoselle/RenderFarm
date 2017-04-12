	/* ICount.java
 * Sample program using BIT -- counts the number of instructions executed.
 *
 * Copyright (c) 1997, The Regents of the University of Colorado. All
 * Rights Reserved.
 * 
 * Permission to use and copy this software and its documentation for
 * NON-COMMERCIAL purposes and without fee is hereby granted provided
 * that this copyright notice appears in all copies. If you wish to use
 * or wish to have others use BIT for commercial purposes please contact,
 * Stephen V. O'Neil, Director, Office of Technology Transfer at the
 * University of Colorado at Boulder (303) 492-5647.
 */

import BIT.highBIT.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;


public class ICount {
    private static PrintStream out = null;
    private static String template = "";

    /* main reads in all the files class files present in the input directory,
     * instruments them, and outputs them to the specified output directory.
     */
    public static void main(String argv[]) {
        File file_in = new File(argv[0]);
        String infilenames[] = file_in.list();

        for (int i = 0; i < infilenames.length; i++) {
            String infilename = infilenames[i];
            if (infilename.startsWith("Main.class")) {
                // create class info object
                ClassInfo ci = new ClassInfo(argv[0] + System.getProperty("file.separator") + infilename);

                // loop through all the routines
                // see java.util.Enumeration for more information on Enumeration class
                for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements(); ) {
                    Routine routine = (Routine) e.nextElement();
                    routine.addBefore("ICount", "methodIn", routine.getMethodName());
                    routine.addAfter("ICount", "methodOut", routine.getMethodName());
                    if (routine.getMethodName().startsWith("render")) {
                        routine.addAfter("ICount", "writeToFile", "Ola");
                    }
                }


                ci.write(argv[1] + System.getProperty("file.separator") + infilename);
            }
        }
    }

    public static synchronized void methodIn(String methodName) {
        template += "Entrei no metodo " + methodName + " Sou a thread " + Thread.currentThread().getId() + "\n";
    }

    public static synchronized void methodOut(String methodName) {
        template += "Sai do metodo " + methodName + " Sou a thread " + Thread.currentThread().getId() + "\n";
    }

    public static synchronized void writeToFile(String ola) {
        try {
            Files.write(Paths.get("metadata.in"), template.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}