package common;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class Item implements Serializable {
    private String itemID;
    private String itemType;
    private String itemName;
    private int itemQuantity;

    private List<Reservation> reservations;
    private Queue<WaitingRequest> waitingQueue;

    public Item(
            String itemID,
            String itemType,
            String itemName,
            int itemQuanitity) {
        this.itemID = itemID;
        this.itemType = itemType;
        this.itemName = itemName;
        this.itemQuantity = itemQuanitity;
        this.reservations = new ArrayList<>();
        this.waitingQueue = new LinkedList<>();
    }

    public String getItemID() {
        return itemID;
    }

    public String getItemType() {
        return itemType;
    }

    public String getItemName() {
        return itemName;
    }

    public int getItemQuantity() {
        return itemQuantity;
    }

    public List<Reservation> getReservations() {
        return reservations;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public void setItemQuantity(int itemQuantity) {
        this.itemQuantity = itemQuantity;
    }

    public void addReservation(Reservation reservation) {
        reservations.add(reservation);
    }

    public void removeReservation(Reservation reservation) {
        reservations.remove(reservation);
    }

    // we loop over the reservations of the item, and we check
    // how many reservations overlap with the inputted
    //
    // returns only the amount available if any (itemQuantity - reserved overlapping
    // at that
    // time frame )
    public int getAvailableUnits(
            LocalDateTime start,
            LocalDateTime end) {
        return getAvilableUnitsExcluding(null, start, end);
    }

    // Manager: quantity update
    // cant update less than max number of overlapping reservations at a time.
    // only checks if update is possible
    // essentially checking if the most reservations at a single time > newQuantity

    public boolean canSetQuantity(int newQuantity) {
        for (Reservation reservation : reservations) {
            int overlaps = 0;

            // checl start time of all the reservations, and check all the overlaps
            // STARTING from that time
            LocalDateTime time = reservation.getStartDateTime();

            for (Reservation other : reservations) {
                // check only the ones that start after the chose one
                if (!time.isBefore(other.getStartDateTime()) && time.isBefore(other.getEndDateTime())) {
                    overlaps++;
                }
            }
            if (overlaps > newQuantity) {
                return false;
            }

        }
        return true;

    }

    // just checks if there are current/future after now
    // that item cannot be removed
    public boolean hasCurrentOrFutureReservations() {
        LocalDateTime now = LocalDateTime.now();

        for (Reservation reservation : reservations) {
            if (reservation.getEndDateTime().isAfter(now)) {
                return true;
            }
        }
        return false;
    }

    // scans reservations until find one that matches the ID
    public Reservation getReservation(String reservationID) {
        for (Reservation reservation : reservations) {
            if (reservation.getReservationID().equals(reservationID)) {
                return reservation;
            }
        }
        return null;
    }

    public boolean removeReservation(String reservationID) {
        for (int i = 0; i < reservations.size(); i++) {
            if (reservations.get(i).getReservationID().equals(reservationID)) {
                reservations.remove(i);
                return true;
            }
        }
        return false;
    }

    // add to queue (FIFO)
    public void addToWaitingQueue(WaitingRequest request) {
        waitingQueue.add(request);
    }

    public Queue<WaitingRequest> getWaitingQueue() {
        return this.waitingQueue;
    }

    public int getCurrentAvailableUnits() {
        LocalDateTime now = LocalDateTime.now();
        int reservedUnits = 0;

        for (Reservation reservation : reservations) {
            if (!now.isBefore(reservation.getStartDateTime()) && now.isBefore(reservation.getEndDateTime())) {
                reservedUnits++;
            }
        }
        return itemQuantity - reservedUnits;
    }

    // cehck available units except self
    public int getAvilableUnitsExcluding(String reservationID, LocalDateTime start, LocalDateTime end) {
        java.util.TreeMap<LocalDateTime, Integer> events = new java.util.TreeMap<>();
        for (Reservation reservation : reservations) {
            if (reservation.getReservationID().equals(reservationID) || !reservation.overlaps(start, end)) {
                continue;
            }
            LocalDateTime clippedStart = reservation.getStartDateTime().isBefore(start)
                    ? start : reservation.getStartDateTime();
            LocalDateTime clippedEnd = reservation.getEndDateTime().isAfter(end)
                    ? end : reservation.getEndDateTime();
            events.merge(clippedStart, 1, Integer::sum);
            events.merge(clippedEnd, -1, Integer::sum);
        }
        int active = 0;
        int peak = 0;
        for (int change : events.values()) {
            active += change;
            peak = Math.max(peak, active);
        }
        return itemQuantity - peak;
    }
}
