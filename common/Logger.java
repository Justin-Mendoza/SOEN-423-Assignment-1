package common;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;

public class Logger {

    public static synchronized void log(
            String campus,
            String operation,
            String actorID,
            String result) {

        try {
            FileWriter writer
                    = new FileWriter(campus + "_server.log", true);

            writer.write(
                    LocalDateTime.now()
                    + " | Server: " + campus
                    + " | Operation: " + operation
                    + " | Actor: " + actorID
                    + " | Result: " + result.replace("\r", "\\r").replace("\n", "\\n")
                    + "\n"
            );

            writer.close();

        } catch (IOException e) {
            System.out.println("Logging failed.");
        }
    }
}
