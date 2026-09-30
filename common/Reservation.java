package common;

import java.io.Serializable;
import java.time.LocalDateTime;

// serialiable since we are using RMI -> 
public class Reservation implements Serializable {
    private String reservationID;
    private String userID;
    private String itemID;

    private LocalDateTime startDateTime;
    private LocalDateTime endDateTime;

    public Reservation(
            String reservationID,
            String userID,
            String itemID,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime) {
        this.reservationID = reservationID;
        this.userID = userID;
        this.itemID = itemID;
        this.startDateTime = startDateTime;
        this.endDateTime = endDateTime;
    }

    public String getReservationID() {
        return reservationID;
    }

    public String getUserID() {
        return userID;
    }

    public String getItemID() {
        return itemID;
    }

    public LocalDateTime getStartDateTime() {
        return startDateTime;
    }

    public LocalDateTime getEndDateTime() {
        return endDateTime;
    }

    public void updateTime(
            LocalDateTime newStartDateTime,
            LocalDateTime newEndDateTime) {
        this.startDateTime = newStartDateTime;
        this.endDateTime = newEndDateTime;
    }

    public boolean overlaps(
            LocalDateTime start,
            LocalDateTime end) {
        return startDateTime.isBefore(end) && endDateTime.isAfter(start);
    }

}
