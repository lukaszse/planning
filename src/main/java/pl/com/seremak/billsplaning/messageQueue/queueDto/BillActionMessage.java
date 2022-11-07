package pl.com.seremak.billsplaning.messageQueue.queueDto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillActionMessage implements Serializable {

    private String categoryName;
    private ActionType type;
    private BigDecimal amount;

    public enum ActionType {
        CREATION, DELETION, UPDATE
    }
}
