package common;

import java.io.Serializable;
import java.time.LocalDateTime;

public class WaitingRequest implements Serializable {
    private String userID;
    private LocalDateTime startDateTime;
    private LocalDateTime endDateTime;
    private LocalDateTime joinedAt;

    public WaitingRequest(
            String userID,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime) {
        this.userID = userID;
        this.startDateTime = startDateTime;
        this.endDateTime = endDateTime;
        this.joinedAt = LocalDateTime.now();
    }

    public String getUserID() {
        return userID;
    }

    public LocalDateTime getStartDateTime() {
        return startDateTime;
    }

    public LocalDateTime getEndDateTime() {
        return endDateTime;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }
}
