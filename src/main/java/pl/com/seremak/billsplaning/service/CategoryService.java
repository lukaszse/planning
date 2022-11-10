package pl.com.seremak.billsplaning.service;

import com.mongodb.lang.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pl.com.seremak.billsplaning.dto.CategoryDto;
import pl.com.seremak.billsplaning.exceptions.ConflictException;
import pl.com.seremak.billsplaning.messageQueue.MessagePublisher;
import pl.com.seremak.billsplaning.messageQueue.queueDto.CategoryDeletionDto;
import pl.com.seremak.billsplaning.model.Category;
import pl.com.seremak.billsplaning.repository.CategoryRepository;
import pl.com.seremak.billsplaning.repository.CategorySearchRepository;
import pl.com.seremak.billsplaning.utils.CollectionUtils;
import pl.com.seremak.billsplaning.utils.VersionedEntityUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;
import static pl.com.seremak.billsplaning.converter.CategoryConverter.*;
import static pl.com.seremak.billsplaning.model.Category.TransactionType.EXPENSE;
import static pl.com.seremak.billsplaning.model.Category.TransactionType.INCOME;
import static pl.com.seremak.billsplaning.utils.BillPlanConstants.MASTER_USER;
import static pl.com.seremak.billsplaning.utils.CollectionUtils.getSoleElementOrThrowException;
import static pl.com.seremak.billsplaning.utils.CollectionUtils.mergeLists;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    public static final String CATEGORY_ALREADY_EXISTS_ERROR_MSG = "Category with name %s for user with name %s already exists";
    public static final String UNDEFINED = "undefined";
    private final CategoryRepository categoryRepository;
    private final CategoryUsageLimitService categoryUsageLimitService;
    private final CategorySearchRepository categorySearchRepository;
    private final MessagePublisher messagePublisher;


    public Mono<Category> createCustomCategory(final String username, final CategoryDto categoryDto) {
        return categoryRepository.findCategoriesByUsernameAndName(username, categoryDto.getName())
                .collectList()
                .mapNotNull(existingCategoryList -> toCategory(username, categoryDto, existingCategoryList))
                .map(VersionedEntityUtils::setMetadata)
                .map(categoryRepository::save)
                .flatMap(mono -> mono)
                .doOnSuccess(this::createNewCategoryUsageLimit)
                .switchIfEmpty(Mono.error(new ConflictException(CATEGORY_ALREADY_EXISTS_ERROR_MSG.formatted(username, categoryDto.getName()))));
    }

    public Flux<Category> createAllCategories(final Set<Category> categories) {
        return categoryRepository.saveAll(categories);
    }

    public Mono<List<Category>> findAllCategories(final String username) {
        return categoryRepository.findCategoriesByUsername(username)
                .collectList();
    }

    public Mono<Category> findCategory(final String username, final String categoryName) {
        return categoryRepository.findCategoriesByUsernameAndName(username, categoryName)
                .collectList()
                .map(CollectionUtils::getSoleElementOrThrowException);
    }

    public Mono<Category> updateCategory(final String username, final CategoryDto categoryDto) {
        final Category categoryToUpdate = toCategory(username, categoryDto);
        return categorySearchRepository.updateCategory(categoryToUpdate)
                .doOnSuccess(this::updateCategoryUsageLimit);
    }

    public Mono<Category> deleteCategory(final String username,
                                         final String categoryName,
                                         @Nullable final String incomingReplacementCategory) {
        return categoryRepository.deleteCategoryByUsernameAndName(username, categoryName)
                .publishOn(Schedulers.boundedElastic())
                .doOnSuccess(category -> reassignTransactionOfDeletedCategory(category, incomingReplacementCategory)
                        .then(categoryUsageLimitService.deleteCategoryUsageLimit(username, categoryName))
                        .subscribe());
    }

    public Mono<List<Category>> createStandardCategoriesForUserIfNotExists(final String username) {
        log.info("Looking for missing standard categories...");
        return findStandardCategoriesForUser(username)
                .collectList()
                .flatMap(userStandardCategories -> findStandardCategoriesForUser(MASTER_USER)
                        .collectList()
                        .map(masterUserStandardCategories ->
                                findAllMissingCategories(username, userStandardCategories, masterUserStandardCategories)))
                .flatMapMany(categoryRepository::saveAll)
                .collectList()
                .doOnSuccess(CategoryService::logMissingCategoryAddingSummary);
    }

    public Flux<Category> findStandardCategoriesForUser(final String username) {
        return categoryRepository.findCategoriesByUsernameAndType(username, Category.Type.STANDARD);
    }

    public static Set<Category> findAllMissingCategories(final String username,
                                                         final List<Category> userStandardCategories,
                                                         final List<String> incomeStandardCategoryNames,
                                                         final List<String> expenseStandardCategoryNames) {
        final List<Category> incomeStandardCategories = toCategories(username, incomeStandardCategoryNames, INCOME);
        final List<Category> expenseStandardCategories = toCategories(username, expenseStandardCategoryNames, EXPENSE);
        final List<Category> allTypeStandardCategories = mergeLists(incomeStandardCategories, expenseStandardCategories);
        return findAllMissingCategories(username, userStandardCategories, allTypeStandardCategories);
    }

    private Mono<String> reassignTransactionOfDeletedCategory(final Category deletedCategory,
                                                              @Nullable final String incomingReplacementCategory) {
        final String replacementCategoryName = defaultIfNull(incomingReplacementCategory, UNDEFINED);
        return findOrCreateUndefinedCategory(deletedCategory.getUsername(), replacementCategoryName, deletedCategory.getTransactionType())
                .doOnNext(existingReplacementCategoryName -> messagePublisher.sentCategoryDeletionMessage(
                        CategoryDeletionDto.of(deletedCategory.getUsername(), deletedCategory.getName(), existingReplacementCategoryName)));
    }

    private static Set<Category> findAllMissingCategories(final String username,
                                                          final List<Category> userStandardCategories,
                                                          final List<Category> masterUserStandardCategories) {
        final Set<String> existingStandardCategoryNamesForUser = extractExistingStandardCategoryNamesForUser(userStandardCategories);
        return masterUserStandardCategories.stream()
                .filter(masterUserStandardCategory -> !existingStandardCategoryNamesForUser.contains(masterUserStandardCategory.getName()))
                .map(masterUserCategoryToCopy -> toCategory(username, masterUserCategoryToCopy.getName(), masterUserCategoryToCopy.getTransactionType()))
                .collect(Collectors.toSet());
    }

    public static void logMissingCategoryAddingSummary(final List<Category> addedCategories) {
        final String addedCategoryNames = addedCategories.stream()
                .map(Category::getName)
                .collect(Collectors.joining(", "));
        if (addedCategories.isEmpty()) {
            log.info("No missing categories found");

        } else {
            log.info("{} missing categories added: {}", addedCategories.size(), addedCategoryNames);
        }
    }

    private void createNewCategoryUsageLimit(final Category category) {
        if (nonNull(category)) {
            categoryUsageLimitService.createNewCategoryUsageLimit(category.getUsername(), category.getName())
                    .subscribe();
        }
    }

    private void updateCategoryUsageLimit(final Category category) {
        if (nonNull(category)) {
            categoryUsageLimitService.updateCategoryUsageLimit(category.getUsername(), category.getName(), category.getLimit())
                    .subscribe();
        }
    }

    private static Set<String> extractExistingStandardCategoryNamesForUser(final List<Category> userStandardCategories) {
        return userStandardCategories.stream()
                .map(Category::getName)
                .collect(Collectors.toSet());
    }

    private Mono<String> findOrCreateUndefinedCategory(final String username,
                                                       final String categoryName,
                                                       final Category.TransactionType transactionType) {
        log.info("No replacement category provided. An undefined category will be find or created if not exist already.");
        return categoryRepository.findCategoriesByUsernameAndName(username, categoryName)
                .collectList()
                .mapNotNull(existingCategoryList -> getSoleElementOrThrowException(existingCategoryList, false))
                .map(Category::getName)
                .doOnNext(existingCategoryName -> log.info("Category with name={} found in database.", existingCategoryName))
                .switchIfEmpty(createCustomCategory(username, toCategoryDto(UNDEFINED, transactionType))
                        .map(Category::getName)
                        .doOnNext(createdCategoryName -> log.info("New category with name={} created.", createdCategoryName)));
    }
}
