package pl.com.seremak.billsplaning.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import pl.com.seremak.billsplaning.dto.TransactionEventDto;
import pl.com.seremak.billsplaning.model.Category;
import pl.com.seremak.billsplaning.model.CategoryUsageLimit;
import pl.com.seremak.billsplaning.repository.CategoryRepository;
import pl.com.seremak.billsplaning.repository.CategoryUsageLimitRepository;
import pl.com.seremak.billsplaning.repository.CategoryUsageLimitSearchRepository;
import pl.com.seremak.billsplaning.utils.CollectionUtils;
import pl.com.seremak.billsplaning.utils.VersionedEntityUtils;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;
import static pl.com.seremak.billsplaning.converter.CategoryUsageLimitConverter.categoryUsageLimitOf;
import static pl.com.seremak.billsplaning.utils.DateUtils.toYearMonthString;
import static pl.com.seremak.billsplaning.utils.TransactionBalanceUtils.updateBalance;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryUsageLimitService {

    private final CategoryUsageLimitRepository categoryUsageLimitRepository;
    private final CategoryUsageLimitSearchRepository categoryUsageLimitSearchRepository;
    private final CategoryRepository categoryRepository;


    public Mono<List<CategoryUsageLimit>> findAllCategoryUsageLimits(final String username, final String yearMonth, final boolean total) {
        final String yearMonthToSearch = defaultIfNull(yearMonth, toYearMonthString(Instant.now()).orElseThrow());
        final Mono<List<CategoryUsageLimit>> categoriesUsageLimitsMono =
                categoryUsageLimitRepository.findByUsernameAndYearMonth(username, yearMonthToSearch)
                        .collectList();
        return total ?
                categoriesUsageLimitsMono.map(CategoryUsageLimitService::extractTotalUsageLimit) :
                categoriesUsageLimitsMono;
    }

    public Mono<CategoryUsageLimit> updateCategoryUsageLimit(final TransactionEventDto transactionEventDto) {
        return categoryUsageLimitRepository.findByUsernameAndCategoryNameAndYearMonth(transactionEventDto.getUsername(),
                        transactionEventDto.getCategoryName(), YearMonth.now().toString())
                .switchIfEmpty(createNewCategoryUsageLimit(transactionEventDto))
                .map(categoryUsageLimit -> updateCategoryUsageLimit(categoryUsageLimit, transactionEventDto))
                .flatMap(categoryUsageLimitSearchRepository::updateCategoryUsageLimit)
                .doOnSuccess(updatedCategoryUsageLimit ->
                        log.info("Usage limit for category={} updated.", updatedCategoryUsageLimit.getCategoryName()));
    }

    public Mono<CategoryUsageLimit> updateCategoryUsageLimit(final String username, final String categoryName, final BigDecimal newLimit) {
        return categoryUsageLimitRepository.findByUsernameAndCategoryNameAndYearMonth(username,
                        categoryName, YearMonth.now().toString())
                .switchIfEmpty(createNewCategoryUsageLimit(username, categoryName))
                .map(categoryUsageLimit -> updateCategoryUsageLimit(categoryUsageLimit, newLimit))
                .flatMap(categoryUsageLimitSearchRepository::updateCategoryUsageLimit)
                .doOnSuccess(updatedCategoryUsageLimit ->
                        log.info("Usage limit for category={} updated.", updatedCategoryUsageLimit.getCategoryName()));
    }

    public Mono<CategoryUsageLimit> createNewCategoryUsageLimit(final String username, final String categoryName) {
        return getCategoryLimit(username, categoryName)
                .map(categoryLimit -> categoryUsageLimitOf(username, categoryName, categoryLimit))
                .map(VersionedEntityUtils::setMetadata)
                .flatMap(categoryUsageLimitRepository::save);
    }

    private Mono<CategoryUsageLimit> createNewCategoryUsageLimit(final TransactionEventDto transactionEventDto) {
        return getCategoryLimit(transactionEventDto.getUsername(), transactionEventDto.getCategoryName())
                .map(categoryLimit -> categoryUsageLimitOf(transactionEventDto, categoryLimit))
                .map(VersionedEntityUtils::setMetadata)
                .flatMap(categoryUsageLimitRepository::save);
    }

    private Mono<BigDecimal> getCategoryLimit(final String username, final String categoryName) {
        return categoryRepository.findCategoriesByUsernameAndName(username, categoryName)
                .collectList()
                .map(CollectionUtils::getSoleElementOrThrowException)
                .map(CategoryUsageLimitService::extractCategoryLimit);
    }

    private static CategoryUsageLimit updateCategoryUsageLimit(final CategoryUsageLimit categoryUsageLimit,
                                                               final TransactionEventDto transactionEventDto) {
        final BigDecimal updatedLimitUsage = updateBalance(categoryUsageLimit.getUsage(), transactionEventDto);
        categoryUsageLimit.setUsage(updatedLimitUsage);
        return categoryUsageLimit;
    }

    private static CategoryUsageLimit updateCategoryUsageLimit(final CategoryUsageLimit categoryUsageLimit,
                                                               final BigDecimal newLimit) {
        categoryUsageLimit.setLimit(newLimit);
        return categoryUsageLimit;
    }

    private static List<CategoryUsageLimit> extractTotalUsageLimit(final List<CategoryUsageLimit> categoryUsageLimits) {
        final Optional<Pair<BigDecimal, BigDecimal>> totalUsageAndLimitOpt = categoryUsageLimits.stream()
                .map(categoryUsageLimit -> Pair.of(categoryUsageLimit.getUsage(), categoryUsageLimit.getLimit()))
                .reduce((usageAndLimit1, usageAndLimit2) ->
                        Pair.of(usageAndLimit1.getFirst().add(usageAndLimit2.getFirst()), usageAndLimit1.getSecond().add(usageAndLimit2.getSecond())));
        final Optional<CategoryUsageLimit> totalOpt = categoryUsageLimits.stream().findFirst()
                .flatMap(category -> totalUsageAndLimitOpt
                        .map(totalUsageAndLimit -> CategoryUsageLimit.builder()
                                .username(category.getUsername())
                                .categoryName("total")
                                .usage(totalUsageAndLimit.getFirst())
                                .limit(totalUsageAndLimit.getSecond())
                                .yearMonth(category.getYearMonth())
                                .build()));
        return totalOpt
                .map(List::of)
                .orElse(List.of());
    }

    private static BigDecimal extractCategoryLimit(final Category category) {
        return Optional.of(category)
                .map(Category::getLimit)
                .orElse(BigDecimal.ZERO);
    }
}
