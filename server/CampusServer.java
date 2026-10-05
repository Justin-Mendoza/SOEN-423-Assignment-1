package server;

import common.Item;
import remote.CampusService;
import common.Reservation;
import common.WaitingRequest;
import common.Logger;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalAdjusters;
import javax.swing.plaf.basic.BasicListUI;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class CampusServer extends UnicastRemoteObject implements CampusService {

    private String campus;
    private Map<String, Item> items; // mapping itemID to item objecty
    private Map<String, List<Reservation>> userReservations; // userID -> Reservations

    private String logResult(String operation, String actorID, String result) {
        Logger.log(campus, operation, actorID, result);
        return result;
    }

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
    public synchronized String joinLocalWaitingQueue(
            String userID,
            String itemID,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime
    ) throws RemoteException {

        Item item = items.get(itemID);

        if (item == null) {
            return logResult("joinLocalWaitingQueue", userID, "FAILURE: Item not found.");
        }

        if (item.getAvailableUnits(startDateTime, endDateTime) > 0) {
            return logResult("joinLocalWaitingQueue", userID, "AVAILABLE: Item can currently be reserved.");
        }

        WaitingRequest request = new WaitingRequest(
                userID,
                startDateTime,
                endDateTime
        );

        item.addToWaitingQueue(request);

        return logResult("joinLocalWaitingQueue", userID, "SUCCESS: Added to waiting queue.");
    }

    @Override
    public synchronized String joinWaitingQueue(
            String userID,
            String itemID,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime
    ) throws RemoteException {

        if (!isValidUser(userID)) {
            return logResult("joinWaitingQueue", userID, "FAILURE: Invalid user ID.");
        }

        if (!isCrossCampusReservationAllowed(
                userID, itemID, startDateTime, endDateTime)) {

            return logResult("joinWaitingQueue", userID, "FAILURE: Cross-campus limit exceeded.");
        }

        if (!isWithinWeeklyBudget(
                userID, startDateTime, endDateTime)) {

            return logResult("joinWaitingQueue", userID, "FAILURE: Weekly budget exceeded.");
        }

        String targetCampus = itemID.substring(0, 3);

        // Item belongs to this campus
        if (targetCampus.equals(campus)) {
            return logResult("joinWaitingQueue", userID, joinLocalWaitingQueue(
                    userID,
                    itemID,
                    startDateTime,
                    endDateTime
            ));
        }

        // Item belongs to another campus
        CampusService targetServer
                = getCampusServer(targetCampus);

        if (targetServer == null) {
            return logResult("joinWaitingQueue", userID, "FAILURE: Target campus unavailable.");
        }

        return logResult("joinWaitingQueue", userID, targetServer.joinLocalWaitingQueue(
                userID,
                itemID,
                startDateTime,
                endDateTime
        ));
    }

    @Override
    public synchronized String findItem(String userID, String itemType, LocalDateTime startDateTime, LocalDateTime endDateTime)
            throws RemoteException {
        if (!isValidUser(userID)) {
            return logResult("findItem", userID, "FAILURE: Invalid User ID");
        }
        if (startDateTime == null || endDateTime == null || !startDateTime.isBefore(endDateTime)) {
            return logResult("findItem", userID, "FAILURE: Invalid time interval.");
        }

        StringBuilder result = new StringBuilder();
        String[] campuses = {"SGW", "LOY", "WIL"};

        for (String targetCampus : campuses) {
            if (targetCampus.equals(campus)) {
                result.append(findLocalItem(itemType, startDateTime, endDateTime));
            } else {
                result.append(findFromCampusWithTimeout(targetCampus, itemType, startDateTime, endDateTime));
            }
        }
        if (result.length() == 0) {
            return logResult("findItem", userID, "No matching items found");
        }
        return logResult("findItem", userID, result.toString());

    }

    @Override
    public synchronized String findLocalItem(String itemType, LocalDateTime start, LocalDateTime end) throws RemoteException {
        StringBuilder result = new StringBuilder();

        for (Item item : items.values()) {
            if (item.getItemType().equalsIgnoreCase(itemType)) {
                int available = item.getAvailableUnits(start, end);

                result.append(item.getItemID())
                        .append(" | ")
                        .append(item.getItemType())
                        .append(" | ")
                        .append(item.getItemName())
                        .append(" | Campus: ")
                        .append(campus)
                        .append(" | Available: ")
                        .append(available)
                        .append("\n");

            }
        }
        return logResult("findLocalItem", "SYSTEM", result.toString());
    }

    @Override
    public synchronized String updateReservation(String userID, String reservationID, LocalDateTime newStart,
            LocalDateTime newEnd) throws RemoteException {

        if (!isValidUser(userID)) {
            return logResult("updateReservation", userID, "FAILURE: Invalid user ID.");
        }

        if (newStart == null || newEnd == null) {
            return logResult("updateReservation", userID, "FAILURE: Invalid date/time.");
        }

        if (!newStart.isBefore(newEnd)) {
            return logResult("updateReservation", userID, "FAILURE: Start time must be before end time.");
        }

        if (newStart.isBefore(now())) {
            return logResult("updateReservation", userID, "FAILURE: Start time cannot be in the past.");
        }

        Reservation reservation = findUserReservation(userID, reservationID);

        if (reservation == null) {
            return logResult("updateReservation", userID, "FAILURE: Reservation not found");
        }

        String itemID = reservation.getItemID();

        if (!isCrossCampusReservationUpdateAllowed(userID, itemID, newStart, newEnd, reservationID)) {
            return logResult("updateReservation", userID, "FAILURE: CROSS CAMPUS LIMIT EXCEEDED");
        }

        if (!isWithinWeeklyBudgetForUpdate(
                userID,
                newStart,
                newEnd,
                reservationID)) {

            return logResult("updateReservation", userID, "FAILURE: Weekly budget exceeded.");
        }

        String targetCampus = itemID.substring(0, 3);
        String result;

        if (targetCampus.equals(campus)) {
            result = updateLocalReservation(userID,
                    reservationID,
                    itemID,
                    newStart,
                    newEnd);

        } else {

            CampusService targetServer = getCampusServer(targetCampus);

            if (targetServer == null) {
                return logResult("updateReservation", userID, "FAILURE: Target campus unavailable.");
            }

            result = targetServer.updateLocalReservation(
                    userID,
                    reservationID,
                    itemID,
                    newStart,
                    newEnd);
        }

        // Only change home-server copy if target update succeeded
        if (result.startsWith("SUCCESS")) {
            reservation.updateTime(
                    newStart,
                    newEnd);
        }

        return logResult("updateReservation", userID, result);
    }

    @Override
    public synchronized String updateLocalReservation(String userID,
            String reservationID,
            String itemID,
            LocalDateTime newStartDateTime,
            LocalDateTime newEndDateTime) throws RemoteException {
        Item item = items.get(itemID);

        if (item == null) {
            return logResult("updateLocalReservation", userID, "FAILURE: Item not found.");
        }

        Reservation reservation = item.getReservation(reservationID);

        if (reservation == null) {
            return logResult("updateLocalReservation", userID, "FAILURE: Reservation not found.");
        }

        if (!reservation.getUserID().equals(userID)) {
            return logResult("updateLocalReservation", userID, "FAILURE: Reservation does not belong to user.");
        }

        if (item.getAvilableUnitsExcluding(
                reservationID,
                newStartDateTime,
                newEndDateTime) <= 0) {

            return logResult("updateLocalReservation", userID, "FAILURE: Item unavailable for new interval.");
        }

        reservation.updateTime(
                newStartDateTime,
                newEndDateTime);

        return logResult("updateLocalReservation", userID, "SUCCESS: Reservation updated.");
    }

    // FIFO
    private void processWaitingQueue(Item item) throws RemoteException {
        Iterator<WaitingRequest> iterator = item.getWaitingQueue().iterator();

        while (iterator.hasNext()) {
            WaitingRequest request = iterator.next();
            // expired
            if (request.getStartDateTime().isBefore(now())) {
                iterator.remove();
            }

            // check availability
            if (item.getAvailableUnits(request.getStartDateTime(), request.getEndDateTime()) <= 0) {
                continue;
            }
            String userID = request.getUserID();
            String homeCampus = userID.substring(0, 3);

            String reservationID = homeCampus + "-R-" + UUID.randomUUID();
            String result;

            if (homeCampus.equals(campus)) {
                result = approveWaitingRequest(userID, userID, reservationID, request.getStartDateTime(),
                        request.getEndDateTime());

            } else {
                // getting user home server
                CampusService homeServer = getCampusServer(homeCampus);
                if (homeServer == null) {
                    continue;
                }

                result = homeServer.approveWaitingRequest(userID, userID, reservationID, request.getStartDateTime(),
                        request.getEndDateTime());

            }
            // within budget adn cross capus approved
            if (result.startsWith("SUCCESS")) {
                Reservation reservation = new Reservation(reservationID, userID, result, request.getStartDateTime(),
                        request.getEndDateTime());
                item.addReservation(reservation);
                iterator.remove();
            }

        }

    }

    @Override
    public synchronized String approveWaitingRequest(String userID, String itemID, String reservationID,
            LocalDateTime start,
            LocalDateTime end) {
        if (!isCrossCampusReservationAllowed(userID, itemID, start, end)) {
            return logResult("approveWaitingRequest", userID, "FAILURE: CROSS CAMPUS LIMIT REACHED");
        }
        if (!isWithinWeeklyBudget(userID, start, end)) {
            return logResult("approveWaitingRequest", userID, "FAILURE: NOT WITHIN WEEKLY BUDGET");
        }

        Reservation reservation = new Reservation(reservationID, userID, itemID, start, end);

        userReservations.computeIfAbsent(userID, key -> new ArrayList<>()).add(reservation);

        return logResult("approveWaitingRequest", userID, "SUCCESS");
    }

    @Override
    public synchronized String cancelReservation(String userID, String ReservationID) throws RemoteException {
        if (!isValidUser(userID)) {
            return logResult("cancelReservation", userID, "FAILURE: User is not a valid user");
        }
        Reservation reservation = findUserReservation((userID), ReservationID);

        if (reservation == null) {
            return logResult("cancelReservation", userID, "FAILURE: reservation not found");
        }
        String item = reservation.getItemID();
        String targetCampus = item.substring(0, 3);
        String result;

        if (targetCampus.equals(campus)) {
            result = cancelLocalReservation(userID, ReservationID, item);
        } else {
            // reservation does not belong to this campus
            CampusService targetServer = getCampusServer(targetCampus);

            if (targetServer == null) {
                return logResult("cancelReservation", userID, "FAILURE: Target Campus not found");
            }

            result = targetServer.cancelLocalReservation(userID, ReservationID, item);

        }
        if (result.startsWith("SUCCESS")) {
            userReservations.get(userID).remove(reservation);
        }

        return logResult("cancelReservation", userID, result);

    }

    @Override
    public synchronized String cancelLocalReservation(String userID, String reservationID, String itemID)
            throws RemoteException {
        Item item = items.get(itemID);
        if (item == null) {
            return logResult("cancelLocalReservation", userID, "FAILURE: ITEM NOT FOUND");
        }

        Reservation reservation = item.getReservation(reservationID);

        if (reservation == null) {
            return logResult("cancelLocalReservation", userID, "FAILURE: RESERVATION NOT FOUND");
        }

        if (!reservation.getUserID().equals(userID)) {
            return logResult("cancelLocalReservation", userID, "FAILURE: USER IDS DO NOT MATCH");
        }

        item.removeReservation(reservation);
        processWaitingQueue(item);
        return logResult("cancelLocalReservation", userID, "SUCCESS: SUCCESSFULLY REMOVED RESERVATION");

    }

    @Override
    public synchronized String reserveItem(String userId, String itemID, LocalDateTime start,
            LocalDateTime end) throws RemoteException {
        // Validation
        if (!isValidUser(userId)) {
            return logResult("reserveItem", userId, "Failure: Not a valid user");
        }
        if (start == null || end == null) {
            return logResult("reserveItem", userId, "Failure: Invalid Date Time");
        }
        if (!start.isBefore(end)) {
            return logResult("reserveItem", userId, "Failure: Start is before end");
        }

        if (start.isBefore(now())) {
            return logResult("reserveItem", userId, "Failure: Start cannot be in th epast");
        }
        // if does pass cross campus limit
        if (!isCrossCampusReservationAllowed(userId, itemID, start, end)) {
            return logResult("reserveItem", userId, "Failure: Cross campus limit is exceeded");
        }

        if (!isWithinWeeklyBudget(userId, start, end)) {
            return logResult("reserveItem", userId, "Failure: exceeds weekly budget");
        }

        // CROSS CAMPUS reservation
        if (!itemID.startsWith(campus)) {
            String targetCampus = campus.substring(0, 3);
            CampusService targetServer = getCampusServer(targetCampus);

            if (targetServer == null) {
                return logResult("reserveItem", userId, "Failure: Target server not found");
            }

            String result = targetServer.reserveLocalItem(userId, itemID, start, end);

            if (result.startsWith("SUCCESS:")) {
                String reservationID = result.substring("SUCCESS:".length());
                Reservation reservation = new Reservation(reservationID, userId, itemID, start, end);
                userReservations.computeIfAbsent(userId, key -> new ArrayList<>()).add(reservation);
            }

            return logResult("reserveItem", userId, result);

        }
        Item item = items.get(itemID);

        if (item == null) {
            return logResult("reserveItem", userId, "Fail: Item not Found");
        }
        if (item.getAvailableUnits(start, end) <= 0) {
            return logResult("reserveItem", userId, "Failure: Item has insufficient units");
        }

        // passes all our checks ,we can reserve it
        String reservationID = campus + "-R-" + System.currentTimeMillis();

        Reservation reservation = new Reservation(reservationID, userId, itemID, start, end);

        // add reservation to item
        item.addReservation(reservation);
        // add reservaiton to the user
        userReservations.computeIfAbsent(userId, key -> new ArrayList<>()).add(reservation);

        return logResult("reserveItem", userId, "SUCCESS: Reservations saved under " + reservationID);
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
            return logResult("addItem", managerID, "Failure: Invalid manager ID.");
        }
        // check iterm in campus
        if (!itemID.startsWith(campus)) {
            return logResult("addItem", managerID, "Failure: Item does not belong to mangers's campus.");
        }
        // check Positive Quantity
        if (itemQuantity <= 0) {
            return logResult("addItem", managerID, "FAILURE: QUANTITY MUST BE GREATER THAN 0.");
        }
        Item item = items.get(itemID);

        // Item does not exist yet
        if (item == null) {
            item = new Item(itemID, itemType, itemName, itemQuantity);
            items.put(itemID, item);
            return logResult("addItem", managerID, "SUCCESS: Item added.");
        }

        if (!item.canSetQuantity(itemQuantity)) {
            return logResult("addItem", managerID, "Failure: Quantity is too small for existing resrvations.");
        }

        item.setItemType(itemType);
        item.setItemName(itemName);
        item.setItemQuantity(itemQuantity);

        return logResult("addItem", managerID, "SUCCESS: Item updated.");

    }

    @Override
    public synchronized String removeItem(
            String managerID,
            String itemID) throws RemoteException {
        if (!isValidManager(managerID)) {
            return logResult("removeItem", managerID, "FAILURE: Invalid managerID.");
        }

        if (!itemID.startsWith(campus)) {
            return logResult("removeItem", managerID, "FAILURE: Item does not belong to manager's campus");
        }

        Item item = items.get(itemID);

        if (item == null) {
            return logResult("removeItem", managerID, "FAILURE: Item not found.");
        }
        if (item.hasCurrentOrFutureReservations()) {
            return logResult("removeItem", managerID, "FAILURE: Item has current/ future reservations.");
        }
        items.remove(itemID);

        return logResult("removeItem", managerID, "SUCCESS: Item removed.");

    }

    @Override
    public synchronized String listAvailableItems(String managerID) throws RemoteException {
        if (!isValidManager(managerID)) {
            return logResult("listAvailableItems", managerID, "FAILURE Invalid Manager ID.");
        }
        if (items.isEmpty()) {
            return logResult("listAvailableItems", managerID, "No items available.");
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
        return logResult("listAvailableItems", managerID, result.toString());
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

    @Override
    public synchronized String reserveLocalItem(String userID, String itemID, LocalDateTime start, LocalDateTime end)
            throws RemoteException {
        if (!itemID.startsWith(campus)) {
            return logResult("reserveLocalItem", userID, "Failure: item does not belong to campus");
        }
        Item item = items.get(itemID);

        if (item == null) {
            return logResult("reserveLocalItem", userID, "Failure: Item does Not Exist");
        }

        if (item.getAvailableUnits(start, end) <= 0) {
            return logResult("reserveLocalItem", userID, "Failrue: Not enough items, cannot reserve");
        }
        String reservationID = campus + "-R-" + UUID.randomUUID();

        Reservation reservation = new Reservation(reservationID, userID, itemID, start, end);

        item.addReservation(reservation);

        return logResult("reserveLocalItem", userID, "SUCCESS: " + reservationID);
    }

    private Reservation findUserReservation(String userID, String reservationID) {
        List<Reservation> reservations = userReservations.get(userID);

        if (reservations == null) {
            return null;
        }

        for (Reservation reservation : reservations) {
            if (reservationID.equals(reservation.getReservationID())) {
                return reservation;

            }
        }
        return null;
    }

    private boolean isCrossCampusReservationUpdateAllowed(String userID, String itemID, LocalDateTime start,
            LocalDateTime end, String reservationID) {

        String homeCampus = userID.substring(0, 3);
        String targetCampus = itemID.substring(0, 3);

        if (homeCampus.equals(targetCampus)) {
            return true;
        }

        List<Reservation> reservations = userReservations.get(userID);

        if (reservations == null) {
            return true;
        }

        for (Reservation reservation : reservations) {
            if (reservation.getReservationID().equals(reservationID)) {
                continue;
            }
            String reservationCampus = reservation.getReservationID().substring(0, 3);
            if (reservationCampus.equals(targetCampus) && reservation.overlaps(start, end)) {
                return false;
            }
        }
        return true;
    }

    private boolean isWithinWeeklyBudgetForUpdate(String userID, LocalDateTime start, LocalDateTime end,
            String reservationID) {
        Map<LocalDateTime, Double> requestedHours = getHoursByWeek(start, end);
        Map<LocalDateTime, Double> usedHours = new HashMap<>();

        List<Reservation> reservations = userReservations.get(userID);

        if (reservations != null) {
            for (Reservation reservation : reservations) {
                if (reservation.getReservationID().equals(reservationID)) {
                    continue;
                }
                Map<LocalDateTime, Double> reservationHours = getHoursByWeek(reservation.getStartDateTime(),
                        reservation.getEndDateTime());

                for (LocalDateTime week : reservationHours.keySet()) {
                    usedHours.put(week, usedHours.getOrDefault(week, 0.0) + reservationHours.get(week));
                }
            }
        }
        for (LocalDateTime week : requestedHours.keySet()) {
            double total = usedHours.getOrDefault(week, 0.0) + requestedHours.get(week);
            if (total > 20.0) {
                return false;
            }
        }
        return true;
    }

    //concurrency timeout helper
    private String findFromCampusWithTimeout(String targetCampus, String itemType, LocalDateTime start, LocalDateTime end) {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<String> future = executor.submit(() -> {
                CampusService server = getCampusServer(targetCampus);

                if (server == null) {
                    throw new RemoteException();
                }
                return server.findLocalItem(itemType, start, end);
            });

            return future.get(2, TimeUnit.SECONDS);

        } catch (Exception e) {
            return targetCampus + ": UNAVAILABLE\n";
        } finally {
            executor.shutdownNow();
        }
    }

}
