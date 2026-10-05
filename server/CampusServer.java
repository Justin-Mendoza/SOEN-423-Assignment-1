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

import java.util.concurrent.ConcurrentHashMap;
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

    // Home-campus checks use a user lock; item methods use this server's monitor.
    // Never hold the server monitor while calling another campus.
    private final Map<String, Object> userLocks = new ConcurrentHashMap<>();
    private boolean processingQueues;
    private boolean queuePassRequested;

    private Object userLock(String userID) {
        return userLocks.computeIfAbsent(String.valueOf(userID), key -> new Object());
    }

    private boolean isValidItemID(String itemID) {
        return itemID != null && itemID.matches("(SGW|LOY|WIL).+");
    }

    private boolean isValidInterval(LocalDateTime start, LocalDateTime end) {
        return start != null && end != null && start.isBefore(end) && !start.isBefore(now());
    }

    private String logResult(String operation, String actorID, String result) {
        Logger.log(campus, operation, actorID, result.isEmpty() ? "SUCCESS: No matching items." : result);
        return result;
    }

    public CampusServer(String campus) throws RemoteException {
        this.campus = campus;
        this.items = new HashMap<>();
        this.userReservations = new ConcurrentHashMap<>();
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

        if (userID == null || !userID.matches("(SGW|LOY|WIL)U\\d{4}")
                || !isValidItemID(itemID) || !itemID.startsWith(campus)
                || !isValidInterval(startDateTime, endDateTime)) {
            return logResult("joinLocalWaitingQueue", userID, "FAILURE: Invalid request.");
        }
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
    public String joinWaitingQueue(String userID, String itemID, LocalDateTime start, LocalDateTime end)
            throws RemoteException {
        synchronized (userLock(userID)) {
            try {
                return joinWaitingQueueState(userID, itemID, start, end);
            } catch (RemoteException e) {
                return logResult("joinWaitingQueue", userID, "FAILURE: " + e.getMessage());
            }
        }
    }

    private String joinWaitingQueueState(
            String userID,
            String itemID,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime
    ) throws RemoteException {

        if (!isValidUser(userID)) {
            return logResult("joinWaitingQueue", userID, "FAILURE: Invalid user ID.");
        }

        if (!isValidItemID(itemID) || !isValidInterval(startDateTime, endDateTime)) {
            return logResult("joinWaitingQueue", userID, "FAILURE: Invalid item ID or time interval.");
        }

        if (!isCrossCampusReservationAllowed(
                userID, itemID, startDateTime, endDateTime)) {

            return logResult("joinWaitingQueue", userID, "FAILURE: Cross-campus limit exceeded.");
        }

        if (!isWithinWeeklyBudget(
                userID, startDateTime, endDateTime)) {

            return logResult("joinWaitingQueue", userID, weeklyBudgetFailure(userID, startDateTime, endDateTime, null));
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
    public String findItem(String userID, String itemType, LocalDateTime startDateTime, LocalDateTime endDateTime)
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
        if (start == null || end == null || !start.isBefore(end)) {
            return logResult("findLocalItem", "SYSTEM", "FAILURE: Invalid time interval.");
        }
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
    public String updateReservation(String userID, String reservationID, LocalDateTime start, LocalDateTime end)
            throws RemoteException {
        String result;
        synchronized (userLock(userID)) {
            try {
                result = updateReservationState(userID, reservationID, start, end);
            } catch (RemoteException e) {
                return logResult("updateReservation", userID, "FAILURE: " + e.getMessage());
            }
        }
        if (result.startsWith("SUCCESS")) {
            processAllWaitingQueues();
        }
        return result;
    }

    private String updateReservationState(String userID, String reservationID, LocalDateTime newStart,
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

            return logResult("updateReservation", userID, weeklyBudgetFailure(userID, newStart, newEnd, reservationID));
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
        if (!isValidInterval(newStartDateTime, newEndDateTime)) {
            return logResult("updateLocalReservation", userID, "FAILURE: Invalid time interval.");
        }
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

    private void processAllWaitingQueues() {
        for (String targetCampus : new String[]{"SGW", "LOY", "WIL"}) {
            try {
                CampusService server = targetCampus.equals(campus) ? this : getCampusServer(targetCampus);
                if (server != null) {
                    server.processWaitingQueues();
                } else {
                    logResult("processWaitingQueues", "SYSTEM", targetCampus + ": UNAVAILABLE");
                }
            } catch (RemoteException e) {
                logResult("processWaitingQueues", "SYSTEM", targetCampus + ": UNAVAILABLE");
            }
        }
    }

    @Override
    public String processWaitingQueues() {
        synchronized (this) {
            queuePassRequested = true;
            if (processingQueues) {
                return logResult("processWaitingQueues", "SYSTEM", "SUCCESS: Queue processing requested.");
            }
            processingQueues = true;
        }
        try {
            while (true) {
                List<Item> snapshot;
                synchronized (this) {
                    queuePassRequested = false;
                    snapshot = new ArrayList<>(items.values());
                }
                for (Item item : snapshot) {
                    processWaitingQueue(item);
                }
                synchronized (this) {
                    if (!queuePassRequested) {
                        processingQueues = false;
                        return logResult("processWaitingQueues", "SYSTEM", "SUCCESS: Waiting queues processed.");
                    }
                }
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                processingQueues = false;
            }
            return logResult("processWaitingQueues", "SYSTEM", "FAILURE: " + e.getMessage());
        }
    }

    // FIFO: the home campus checks eligibility and reserves the unit under its user lock.
    private void processWaitingQueue(Item item) {
        List<WaitingRequest> requests;
        synchronized (this) {
            requests = new ArrayList<>(item.getWaitingQueue());
        }
        for (WaitingRequest request : requests) {
            synchronized (this) {
                if (items.get(item.getItemID()) != item) {
                    return;
                }
                if (request.getStartDateTime().isBefore(now())) {
                    item.getWaitingQueue().remove(request);
                    continue;
                }
                if (item.getAvailableUnits(request.getStartDateTime(), request.getEndDateTime()) <= 0) {
                    continue;
                }
            }
            String userID = request.getUserID();
            String homeCampus = userID.substring(0, 3);
            CampusService homeServer = homeCampus.equals(campus) ? this : getCampusServer(homeCampus);
            if (homeServer == null) {
                continue;
            }
            try {
                String result = homeServer.approveWaitingRequest(userID, item.getItemID(),
                        campus + "-R-" + UUID.randomUUID(), request.getStartDateTime(), request.getEndDateTime());
                if (result.startsWith("SUCCESS")) {
                    synchronized (this) {
                        item.getWaitingQueue().remove(request);
                    }
                }
            } catch (RemoteException e) {
                logResult("processWaitingQueue", userID, "FAILURE: Home campus unavailable.");
            }
        }
    }

    @Override
    public String approveWaitingRequest(String userID, String itemID, String reservationID,
            LocalDateTime start, LocalDateTime end) throws RemoteException {
        synchronized (userLock(userID)) {
            if (!isValidUser(userID) || !isValidItemID(itemID) || !isValidInterval(start, end)) {
                return logResult("approveWaitingRequest", userID, "FAILURE: Invalid request.");
            }
            // reserveItem checks the budget/limit, commits capacity, and records the actual item and ID.
            try {
                return logResult("approveWaitingRequest", userID, reserveItemState(userID, itemID, start, end));
            } catch (RemoteException e) {
                return logResult("approveWaitingRequest", userID, "FAILURE: " + e.getMessage());
            }
        }
    }

    @Override
    public String cancelReservation(String userID, String reservationID) throws RemoteException {
        String result;
        synchronized (userLock(userID)) {
            try {
                result = cancelReservationState(userID, reservationID);
            } catch (RemoteException e) {
                return logResult("cancelReservation", userID, "FAILURE: " + e.getMessage());
            }
        }
        // Credit the user's budget before queue eligibility checks and callbacks.
        if (result.startsWith("SUCCESS")) {
            processAllWaitingQueues();
        }
        return result;
    }

    private String cancelReservationState(String userID, String ReservationID) throws RemoteException {
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
        return logResult("cancelLocalReservation", userID, "SUCCESS: SUCCESSFULLY REMOVED RESERVATION");

    }

    @Override
    public String reserveItem(String userID, String itemID, LocalDateTime start, LocalDateTime end)
            throws RemoteException {
        synchronized (userLock(userID)) {
            try {
                return reserveItemState(userID, itemID, start, end);
            } catch (RemoteException e) {
                return logResult("reserveItem", userID, "FAILURE: " + e.getMessage());
            }
        }
    }

    private String reserveItemState(String userId, String itemID, LocalDateTime start,
            LocalDateTime end) throws RemoteException {
        // Validation
        if (!isValidUser(userId)) {
            return logResult("reserveItem", userId, "Failure: Not a valid user");
        }
        if (!isValidItemID(itemID)) {
            return logResult("reserveItem", userId, "FAILURE: Invalid item ID.");
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
            return logResult("reserveItem", userId, weeklyBudgetFailure(userId, start, end, null));
        }

        // CROSS CAMPUS reservation
        if (!itemID.startsWith(campus)) {
            String targetCampus = itemID.substring(0, 3);
            CampusService targetServer = getCampusServer(targetCampus);

            if (targetServer == null) {
                return logResult("reserveItem", userId, "Failure: Target server not found");
            }

            String result = targetServer.reserveLocalItem(userId, itemID, start, end);

            if (result.startsWith("SUCCESS:")) {
                String reservationID = result.substring("SUCCESS:".length()).trim();
                Reservation reservation = new Reservation(reservationID, userId, itemID, start, end);
                userReservations.computeIfAbsent(userId, key -> new ArrayList<>()).add(reservation);
            }

            return logResult("reserveItem", userId, result);

        }
        return reserveHomeItem(userId, itemID, start, end);
    }

    private synchronized String reserveHomeItem(String userId, String itemID, LocalDateTime start,
            LocalDateTime end) {
        Item item = items.get(itemID);

        if (item == null) {
            return logResult("reserveItem", userId, "Fail: Item not Found");
        }
        if (item.getAvailableUnits(start, end) <= 0) {
            return logResult("reserveItem", userId, "UNAVAILABLE: Item has insufficient units");
        }

        // passes all our checks ,we can reserve it
        String reservationID = campus + "-R-" + UUID.randomUUID();

        Reservation reservation = new Reservation(reservationID, userId, itemID, start, end);

        // add reservation to item
        item.addReservation(reservation);
        // add reservaiton to the user
        userReservations.computeIfAbsent(userId, key -> new ArrayList<>()).add(reservation);

        return logResult("reserveItem", userId, "SUCCESS: Reservations saved under " + reservationID);
    }

    // ADDITEM BUT ALSO UDPATE FOR GIVEN SERVER
    @Override
    public String addItem(String managerID, String itemID, String itemType, String itemName, int quantity)
            throws RemoteException {
        String result = addItemState(managerID, itemID, itemType, itemName, quantity);
        if (result.startsWith("SUCCESS")) {
            processWaitingQueues();
        }
        return result;
    }

    private synchronized String addItemState(
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
        if (!isValidItemID(itemID) || !itemID.startsWith(campus)) {
            return logResult("addItem", managerID, "Failure: Item does not belong to mangers's campus.");
        }
        if (itemType == null || itemType.isBlank() || itemName == null || itemName.isBlank()) {
            return logResult("addItem", managerID, "FAILURE: Item type and name are required.");
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

        if (!isValidItemID(itemID) || !itemID.startsWith(campus)) {
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

    private boolean isWithinWeeklyBudget(String userID, LocalDateTime start, LocalDateTime end) {
        return weeklyBudgetFailure(userID, start, end, null) == null;
    }

    private String weeklyBudgetFailure(String userID, LocalDateTime start, LocalDateTime end,
            String excludedReservationID) {
        Map<LocalDateTime, Double> requested = new java.util.TreeMap<>(getHoursByWeek(start, end));
        Map<LocalDateTime, Double> used = new HashMap<>();
        for (Reservation reservation : userReservations.getOrDefault(userID, java.util.Collections.emptyList())) {
            if (reservation.getReservationID().equals(excludedReservationID)) {
                continue;
            }
            getHoursByWeek(reservation.getStartDateTime(), reservation.getEndDateTime())
                    .forEach((week, hours) -> used.merge(week, hours, Double::sum));
        }
        boolean exceeded = false;
        StringBuilder message = new StringBuilder("FAILURE: Weekly budget exceeded.");
        for (Map.Entry<LocalDateTime, Double> entry : requested.entrySet()) {
            double remaining = Math.max(0, 20 - used.getOrDefault(entry.getKey(), 0.0));
            exceeded |= entry.getValue() > remaining;
            message.append(String.format(java.util.Locale.ROOT,
                    " Week of %s: %.2f hours remaining; %.2f hours requested.",
                    entry.getKey().toLocalDate(), remaining, entry.getValue()));
        }
        return exceeded ? message.toString() : null;
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
        if (userID == null || !userID.matches("(SGW|LOY|WIL)U\\d{4}")
                || !isValidItemID(itemID) || !isValidInterval(start, end)) {
            return logResult("reserveLocalItem", userID, "FAILURE: Invalid request.");
        }
        if (!itemID.startsWith(campus)) {
            return logResult("reserveLocalItem", userID, "Failure: item does not belong to campus");
        }
        Item item = items.get(itemID);

        if (item == null) {
            return logResult("reserveLocalItem", userID, "Failure: Item does Not Exist");
        }

        if (item.getAvailableUnits(start, end) <= 0) {
            return logResult("reserveLocalItem", userID, "UNAVAILABLE: Not enough items, cannot reserve");
        }
        String reservationID = campus + "-R-" + UUID.randomUUID();

        Reservation reservation = new Reservation(reservationID, userID, itemID, start, end);

        item.addReservation(reservation);

        return logResult("reserveLocalItem", userID, "SUCCESS: " + reservationID);
    }

    private Reservation findUserReservation(String userID, String reservationID) {
        List<Reservation> reservations = userReservations.get(userID);

        if (reservations == null || reservationID == null) {
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
            String reservationCampus = reservation.getItemID().substring(0, 3);
            if (reservationCampus.equals(targetCampus) && reservation.overlaps(start, end)) {
                return false;
            }
        }
        return true;
    }

    private boolean isWithinWeeklyBudgetForUpdate(String userID, LocalDateTime start, LocalDateTime end,
            String reservationID) {
        return weeklyBudgetFailure(userID, start, end, reservationID) == null;
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
