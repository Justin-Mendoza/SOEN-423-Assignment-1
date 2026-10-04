package remote;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.time.LocalDateTime;

public interface CampusService extends Remote {

    String addItem(
            String managerID,
            String itemID,
            String itemType,
            String itemName,
            int itemQuantity) throws RemoteException;

    String removeItem(
            String managerID,
            String itemID) throws RemoteException;

    String listAvailableItems(
            String managerID) throws RemoteException;

    String reserveItem(String userID, String itemID, LocalDateTime startDateTime, LocalDateTime endDateTime)
            throws RemoteException;

    String updateReservation(String userID, String reservationID, LocalDateTime startDateTime,
            LocalDateTime endDateTime)
            throws RemoteException;

    String cancelReservation(
            String userID,
            String reservationID) throws RemoteException;

    // find Matching items of type accorss all campuses
    String findItem(String userID, String itemType, LocalDateTime startDateTime, LocalDateTime endDateTime)
            throws RemoteException;

    // to reserve itm under other campus
    String reserveLocalItem(String userID, String itemID, LocalDateTime startDateTime, LocalDateTime endDateTime)
            throws RemoteException;

    String cancelLocalReservation(String userID, String reservationID, String itemID) throws RemoteException;

    String approveWaitingRequest(String userID, String itemID, String reservationID, LocalDateTime startTime,
            LocalDateTime endTime) throws RemoteException;

    String updateReservetion(String userID, String itemID, String reservationID, LocalDateTime startTime,
            LocalDateTime endTime) throws RemoteException;

    public String updateLocalReservation(String userID, String reservationID, String itemID,
            LocalDateTime newStartDateTime, LocalDateTime newEndDateTime) throws RemoteException;

    public String findLocalItem(String itemType, LocalDateTime startTime, LocalDateTime endTime) throws RemoteException;

    public String joinWaitingQueue(String userID, String itemID, LocalDateTime startDateTime, LocalDateTime endDateTime
    ) throws RemoteException;

    String joinLocalWaitingQueue(String userID, String itemID, LocalDateTime startDateTime, LocalDateTime endDateTime
    ) throws RemoteException;
}
