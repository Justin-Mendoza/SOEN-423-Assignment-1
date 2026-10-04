package server;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class ServerMain {
    public static void main(String[] args) {
        try {
            Registry registry = LocateRegistry.createRegistry(1099);

            CampusServer sgwServer = new CampusServer("SGW");
            CampusServer loyServer = new CampusServer("LOY");
            CampusServer wilServer = new CampusServer("WIL");

            registry.rebind("SGW", sgwServer);
            registry.rebind("LOY", loyServer);
            registry.rebind("WIL", wilServer);

            System.out.println("All Campus Servers are running.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
