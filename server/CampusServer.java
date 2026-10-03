package server;

import common.Item;
import remote.CampusService;
import common.Reservation;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalAdjusters;
import javax.swing.plaf.basic.BasicListUI;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class CampusServer extends UnicastRemoteObject implements CampusService {
    private String campus;
    private Map<String, Item> items; // mapping itemID to item objecty
    private Map<String, List<Reservation>> userReservations; // userID -> Reservations

    public CampusServer(String campus) throws RemoteException {
        this.campus = campus;
        this.items = new HashMap<>();
        this.userReservations = new HashMap<>();
    }

    private boolean isValidManager(String managerID) {
        if (managerID == null) {
            return false;
        }

        return managerID.matches("(SGW|LOY|WIL)M\\d{4}") && managerID.startsWith(campus);
    }

    @Override
    public synchronized String reserveItem(String userId, String itemID, LocalDateTime start,
            LocalDateTime end) throws RemoteException {
        // Validation
        if (!isValidUser(userId)) {
            return "Failure: Not a valid user";
        }
        if (start == null || end == null) {
            return "Failure: Invalid Date Time";
        }
        if (!start.isBefore(end)) {
            return "Failure: Start is before end";
        }

        if (start.isBefore(now())) {
            return "Failure: Start cannot be in th epast";
        }
        // if does pass cross campus limit
        if (!isCrossCampusReservationAllowed(userId, itemID, start, end)) {
            return "Failure: Cross campus limit is exceeded";
        }

        if (!isWithinWeeklyBudget(userId, start, end)) {
            return "Failure: exceeds weekly budget";
        }
        if (!itemID.startsWith(campus)) {
            return "FAILURE: Cross-campus routing not implemented yet";

        }
        Item item = items.get(itemID);

        if (item == null) {
            return "Fail: Item not Found";
        }
        if (item.getAvailableUnits(start, end) <= 0) {
            return "Failure: Item has insufficient units";
        }

        // passes all our checks ,we can reserve it
        String reservationID = campus + "-R-" + System.currentTimeMillis();

        Reservation reservation = new Reservation(reservationID, userId, itemID, start, end);

        // add reservation to item
        item.addReservation(reservation);
        // add reservaiton to the user
        userReservations.computeIfAbsent(userId, key -> new ArrayList<>()).add(reservation);

        return "Success: Reservations saved under " + reservationID;
    }

    // ADDITEM BUT ALSO UDPATE FOR GIVEN SERVER
    @Override
    public synchronized String addItem(
            String managerID,
            String itemID,
            String itemType,
            String itemName,
            int itemQuantity) throws RemoteException {
        // check manager
        if (!isValidManager(managerID)) {
            return "Failure: Invalid manager ID.";
        }
        // check iterm in campus
        if (!itemID.startsWith(campus)) {
            return "Failure: Item does not belong to mangers's campus.";
        }
        // check Positive Quantity
        if (itemQuantity <= 0) {
            return "FAILURE: QUANTITY MUST BE GREATER THAN 0.";
        }
        Item item = items.get(itemID);

        // Item does not exist yet
        if (item == null) {
            item = new Item(itemID, itemType, itemName, itemQuantity);
            items.put(itemID, item);
            return "Success: Item added.";
        }

        if (!item.canSetQuantity(itemQuantity)) {
            return "Failure: Quantity is too small for existing resrvations.";
        }

        item.setItemType(itemType);
        item.setItemName(itemName);
        item.setItemQuantity(itemQuantity);

        return "SUCCESS: Item updated.";

    }

    @Override
    public synchronized String removeItem(
            String managerID,
            String itemID) throws RemoteException {
        if (!isValidManager(managerID)) {
            return "FAILURE: Invalid managerID.";
        }

        if (!itemID.startsWith(campus)) {
            return "FAILURE: Item does not belong to manager's campus";
        }

        Item item = items.get(itemID);

        if (item == null) {
            return "FAILURE: Item not found.";
        }
        if (item.hasCurrentOrFutureReservations()) {
            return "FAILURE: Item has current/ future reservations.";
        }
        items.remove(itemID);

        return "SUCCESS: Item removed.";

    }

    @Override
    public synchronized String listAvailableItems(String managerID) throws RemoteException {
        if (!isValidManager(managerID)) {
            return "FAILURE Invalid Manager ID.";
        }
        if (items.isEmpty()) {
            return "No items available.";
        }
        StringBuilder result = new StringBuilder();

        for (Item item : items.values()) {
            result.append(item.getItemID())
                    .append(" | ")
                    .append(item.getItemType())
                    .append(" | ")
                    .append(item.getItemName())
                    .append(" | Available: ")
                    .append(item.getCurrentAvailableUnits())
                    .append("\n");
        }
        return result.toString();
    }

    private boolean isValidUser(String userID) {
        if (userID == null) {
            return false;
        }

        return userID.matches("(SGW|LOY|WIL)U\\d{4}")
                && userID.startsWith(campus);
    }

    private boolean isCrossCampusReservationAllowed(
            String userID,
            String itemID,
            LocalDateTime start,
            LocalDateTime end) {
        String homeCampus = userID.substring(0, 3);
        String targetCampus = itemID.substring(0, 3);

        // Home Campus reservations have no cross campus limit

        if (homeCampus.equals(targetCampus)) {
            return true;
        }

        List<Reservation> reservations = userReservations.get(userID);

        if (reservations == null) {
            return true;
        }
        for (Reservation reservation : reservations) {
            String reservationCampus = reservation.getItemID().substring(0, 3);

            if (reservationCampus.equals(targetCampus) && reservation.overlaps(start, end)) {
                return false;
            }
        }
        return true;
    }

    // find starting monday of a given day (passed in)
    private LocalDateTime getWeekStart(LocalDateTime dateTime) {
        LocalDate monday = dateTime.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.atStartOfDay();
    }

    // TODO: remember
    private Map<LocalDateTime, Double> getHoursByWeek(
            LocalDateTime start,
            LocalDateTime end) {
        Map<LocalDateTime, Double> hoursByWeek = new HashMap<>();

        LocalDateTime current = start;

        while (current.isBefore(end)) {
            LocalDateTime weekStart = getWeekStart(current);
            LocalDateTime nextWeek = weekStart.plusWeeks(1);

            LocalDateTime sectionEnd;

            if (end.isBefore(nextWeek)) {
                sectionEnd = end;
            } else {
                sectionEnd = nextWeek;
            }

            double hours = Duration.between(current, sectionEnd).toMinutes() / 60.0;

            hoursByWeek.put(weekStart, hoursByWeek.getOrDefault(weekStart, 0.0) + hours);

            current = sectionEnd;
        }

        return hoursByWeek;

    }

    // TODO: Remember
    private boolean isWithinWeeklyBudget(String userID, LocalDateTime start, LocalDateTime end) {
        Map<LocalDateTime, Double> usedHours = new HashMap<>();

        Map<LocalDateTime, Double> requestedHours = getHoursByWeek(start, end);

        List<Reservation> reservations = userReservations.get(userID);

        if (reservations != null) {
            for (Reservation reservation : reservations) {
                Map<LocalDateTime, Double> reservationHours = getHoursByWeek(reservation.getStartDateTime(),
                        reservation.getEndDateTime());

                for (LocalDateTime week : reservationHours.keySet()) {
                    usedHours.put(week, usedHours.getOrDefault(week, 0.0) + reservationHours.get(week));
                }

            }
        }
        for (LocalDateTime week : requestedHours.keySet()) {
            double total = usedHours.getOrDefault(week, 0.0) + requestedHours.get(week);
            if (total > 20) {
                return false;
            }
        }

        return true;
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    // gives remote reference to calling campus
    private CampusService getCampusServer(String targetCampus) {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);

            return (CampusService) registry.lookup(targetCampus);
        } catch (Exception e) {
            return null;
        }
    }

}
