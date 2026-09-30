package server;

import common.Item;
import remote.CampusService;
import common.Reservation;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.time.LocalDateTime;

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

    // ADDITEM BUT ALSO UDPATE
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

    private boolean passesCrossCampusLimit(
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
}
