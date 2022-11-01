package pl.com.seremak.billsplaning.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import pl.com.seremak.billsplaning.model.Expense;
import pl.com.seremak.billsplaning.utils.MongoQueryHelper;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class ExpenseSearchRepository {

    private final ReactiveMongoTemplate mongoTemplate;
    private final MongoQueryHelper mongoQueryHelper;

    public Mono<Expense> updateExpense(final Expense expense) {
        return mongoTemplate.findAndModify(
                prepareFindBillQuery(expense.getUsername(), expense.getCategoryName()),
                mongoQueryHelper.preparePartialUpdateQuery(expense),
                new FindAndModifyOptions().returnNew(true),
                Expense.class);
    }

    private static Query prepareFindBillQuery(final String username, final String categoryName) {
        return new Query()
                .addCriteria(Criteria.where("username").is(username))
                .addCriteria(Criteria.where("categoryName").is(categoryName));
    }
}
