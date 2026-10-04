package client;

import remote.CampusService;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Scanner;

public class Client {

    private static final Scanner scanner = new Scanner(System.in);

    //   date format for our client
    private static final DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");


    private static CampusService connectToHomeCampus(String id) {

        try {

            if (id == null || id.length() < 3) {
                return null;
            }

            String campus = id.substring(0, 3);

            Registry registry =
                    LocateRegistry.getRegistry("localhost", 1099);

            return (CampusService) registry.lookup(campus);

        } catch (Exception e) {

            return null;
        }
    }


    public static void main(String[] args) {

        System.out.print("Enter your ID: ");
        String id = scanner.nextLine();

        CampusService server = connectToHomeCampus(id);

        if (server == null) {
            System.out.println("Could not connect to campus server.");
            return;
        }

        try {

            // Example: SGWM1111
            if (id.length() >= 4 && id.charAt(3) == 'M') {

                managerMenu(server, id);

            }

            // Example: SGWU1111
            else if (id.length() >= 4 && id.charAt(3) == 'U') {

                userMenu(server, id);

            }

            else {
                System.out.println("Invalid ID.");
            }

        } catch (Exception e) {

            System.out.println("ERROR: " + e.getMessage());
        }
    }


    // manager menu 

    private static void managerMenu(
            CampusService server,
            String managerID) throws Exception {

        while (true) {

            System.out.println("\n--- MANAGER MENU ---");
            System.out.println("1. Add / Update Item");
            System.out.println("2. Remove Item");
            System.out.println("3. List Available Items");
            System.out.println("0. Exit");

            System.out.print("Choice: ");
            String choice = scanner.nextLine();

            if (choice.equals("1")) {

                System.out.print("Item ID: ");
                String itemID = scanner.nextLine();

                System.out.print("Item Type: ");
                String itemType = scanner.nextLine();

                System.out.print("Item Name: ");
                String itemName = scanner.nextLine();

                System.out.print("Quantity: ");
                int quantity =
                        Integer.parseInt(scanner.nextLine());

                String result = server.addItem(
                        managerID,
                        itemID,
                        itemType,
                        itemName,
                        quantity
                );

                System.out.println(result);
            }

            else if (choice.equals("2")) {

                System.out.print("Item ID: ");
                String itemID = scanner.nextLine();

                String result =
                        server.removeItem(managerID, itemID);

                System.out.println(result);
            }

            else if (choice.equals("3")) {

                String result =
                        server.listAvailableItems(managerID);

                System.out.println(result);
            }

            else if (choice.equals("0")) {

                break;
            }

            else {

                System.out.println("Invalid choice.");
            }
        }
    }


    // user menu

    private static void userMenu(
            CampusService server,
            String userID) throws Exception {

        while (true) {

            System.out.println("\n--- USER MENU ---");
            System.out.println("1. Reserve Item");
            System.out.println("2. Update Reservation");
            System.out.println("3. Cancel Reservation");
            System.out.println("4. Find Item");
            System.out.println("0. Exit");

            System.out.print("Choice: ");
            String choice = scanner.nextLine();

            if (choice.equals("1")) {

                reserveItem(server, userID);
            }

            else if (choice.equals("2")) {

                updateReservation(server, userID);
            }

            else if (choice.equals("3")) {

                cancelReservation(server, userID);
            }

            else if (choice.equals("4")) {

                findItem(server, userID);
            }

            else if (choice.equals("0")) {

                break;
            }

            else {

                System.out.println("Invalid choice.");
            }
        }
    }


    // reserve

    private static void reserveItem(
            CampusService server,
            String userID) throws Exception {

        System.out.print("Item ID: ");
        String itemID = scanner.nextLine();

        LocalDateTime start =
                readDateTime("Start time");

        LocalDateTime end =
                readDateTime("End time");

        String result = server.reserveItem(
                userID,
                itemID,
                start,
                end
        );

        System.out.println(result);

        // Assignment says user MAY choose to join queue
        if (result.startsWith("UNAVAILABLE")) {

            System.out.print("Join waiting queue? (y/n): ");
            String answer = scanner.nextLine();

            if (answer.equalsIgnoreCase("y")) {

                String queueResult =
                        server.joinWaitingQueue(
                                userID,
                                itemID,
                                start,
                                end
                        );

                System.out.println(queueResult);
            }
        }
    }


    //update

    private static void updateReservation(
            CampusService server,
            String userID) throws Exception {

        System.out.print("Reservation ID: ");
        String reservationID = scanner.nextLine();

        LocalDateTime newStart =
                readDateTime("New start time");

        LocalDateTime newEnd =
                readDateTime("New end time");

        String result =
                server.updateReservation(
                        userID,
                        reservationID,
                        newStart,
                        newEnd
                );

        System.out.println(result);
    }


    // cancel

    private static void cancelReservation(
            CampusService server,
            String userID) throws Exception {

        System.out.print("Reservation ID: ");
        String reservationID = scanner.nextLine();

        String result =
                server.cancelReservation(
                        userID,
                        reservationID
                );

        System.out.println(result);
    }


    // find 

    private static void findItem(
            CampusService server,
            String userID) throws Exception {

        System.out.print("Item type: ");
        String itemType = scanner.nextLine();

        LocalDateTime start =
                readDateTime("Start time");

        LocalDateTime end =
                readDateTime("End time");

        String result =
                server.findItem(
                        userID,
                        itemType,
                        start,
                        end
                );

        System.out.println(result);
    }


    // date input

    private static LocalDateTime readDateTime(String message) {

        while (true) {

            System.out.print(
                    message + " (yyyy-MM-dd HH:mm): "
            );

            String input = scanner.nextLine();

            try {

                return LocalDateTime.parse(
                        input,
                        formatter
                );

            } catch (DateTimeParseException e) {

                System.out.println(
                        "Invalid format. Example: 2026-10-10 14:30"
                );
            }
        }
    }
}