package teammates.storage.api;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.jdo.JDOHelper;
import javax.jdo.JDOObjectNotFoundException;
import javax.jdo.Query;




import javax.jdo.Transaction;

import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;

import teammates.common.datatransfer.EntityAttributes;
import teammates.common.datatransfer.FeedbackQuestionAttributes;
import teammates.common.datatransfer.FeedbackSessionAttributes;
import teammates.common.datatransfer.FeedbackSessionType;
import teammates.common.exception.EntityAlreadyExistsException;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.exception.InvalidParametersException;
import teammates.common.util.Assumption;
import teammates.common.util.Const;
import teammates.common.util.TimeHelper;
import teammates.storage.entity.FeedbackSession;

public class FeedbackSessionsDb extends EntitiesDb {
    
    public static final String ERROR_UPDATE_NON_EXISTENT = "Trying to update non-existent Feedback Session : ";

    public void createFeedbackSessions(Collection<FeedbackSessionAttributes> feedbackSessionsToAdd)
            throws InvalidParametersException {
        List<EntityAttributes> feedbackSessionsToUpdate = createEntities(feedbackSessionsToAdd);
        for (EntityAttributes entity : feedbackSessionsToUpdate) {
            FeedbackSessionAttributes session = (FeedbackSessionAttributes) entity;
            try {
                updateFeedbackSession(session);
            } catch (EntityDoesNotExistException e) {
             // This situation is not tested as replicating such a situation is
             // difficult during testing
                Assumption.fail("Entity found be already existing and not existing simultaneously");
            }
        }
    }
       
    public List<FeedbackSessionAttributes> getAllOpenFeedbackSessions(Date start, Date end, double zone) {
        
        List<FeedbackSessionAttributes> list = new LinkedList<FeedbackSessionAttributes>();
        
        final Query endTimequery = getPm().newQuery("SELECT FROM teammates.storage.entity.FeedbackSession "
                                                    + "WHERE this.endTime>rangeStart && this.endTime<=rangeEnd "
                                                    + " PARAMETERS java.util.Date rangeStart, "
                                                    + "java.util.Date rangeEnd");

        final Query startTimequery = getPm().newQuery("SELECT FROM teammates.storage.entity.FeedbackSession "
                                                      + "WHERE this.startTime>=rangeStart && this.startTime<rangeEnd "
                                                      + "PARAMETERS java.util.Date rangeStart, "
                                                      + "java.util.Date rangeEnd");
            
        Calendar startCal = Calendar.getInstance();
        startCal.setTime(start);
        Calendar endCal = Calendar.getInstance();
        endCal.setTime(end);

        Date curStart = TimeHelper.convertToUserTimeZone(startCal, -25).getTime();
        Date curEnd = TimeHelper.convertToUserTimeZone(endCal, 25).getTime();
     
        @SuppressWarnings("unchecked")
        List<FeedbackSession> endEntities = (List<FeedbackSession>) endTimequery.execute(curStart, curEnd);
        @SuppressWarnings("unchecked")
        List<FeedbackSession> startEntities = (List<FeedbackSession>) startTimequery.execute(curStart, curEnd);
        
        List<FeedbackSession> endTimeEntities = new ArrayList<FeedbackSession>(endEntities);
        List<FeedbackSession> startTimeEntities = new ArrayList<FeedbackSession>(startEntities);
        
        endTimeEntities.removeAll(startTimeEntities);
        startTimeEntities.removeAll(endTimeEntities);
        endTimeEntities.addAll(startTimeEntities);
                    
        
        Iterator<FeedbackSession> it = endTimeEntities.iterator();

        while (it.hasNext()) {
            FeedbackSession feedbackSession = it.next();
            
            // Continue to the next element if the current element is deleted
            if (JDOHelper.isDeleted(feedbackSession)) {
                continue;
            }
            
            startCal.setTime(start);
            endCal.setTime(end);
            FeedbackSessionAttributes fs = new FeedbackSessionAttributes(feedbackSession);
            
            Date standardStart = TimeHelper.convertToUserTimeZone(startCal, fs.getTimeZone() - zone).getTime();
            Date standardEnd = TimeHelper.convertToUserTimeZone(endCal, fs.getTimeZone() - zone).getTime();
            
            boolean isStartTimeWithinRange = TimeHelper.isTimeWithinPeriod(standardStart,
                                                                           standardEnd,
                                                                           fs.getStartTime(),
                                                                           true,
                                                                           false);
            boolean isEndTimeWithinRange = TimeHelper.isTimeWithinPeriod(standardStart,
                                                                         standardEnd,
                                                                         fs.getEndTime(),
                                                                         false,
                                                                         true);

            if (isStartTimeWithinRange || isEndTimeWithinRange) {
                list.add(fs);
            }
        }
             
        return list;
    }

    
    /**
     * Preconditions: <br>
     * * All parameters are non-null.
     * @return Null if not found.
     */
    public FeedbackSessionAttributes getFeedbackSession(String courseId, String feedbackSessionName) {
        
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, feedbackSessionName);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseId);
        
        FeedbackSession fs = getFeedbackSessionEntity(feedbackSessionName, courseId);
        
        if (fs == null || JDOHelper.isDeleted(fs)) {
            log.info("Trying to get non-existent Session: " + feedbackSessionName + "/" + courseId);
            return null;
        }
        return new FeedbackSessionAttributes(fs);
        
    }
    
    /**
     * @return empty list if none found.
     * @deprecated Not scalable. Created for data migration purposes.
     */
    @Deprecated
    public List<FeedbackSessionAttributes> getAllFeedbackSessions() {
        List<FeedbackSession> allFs = getAllFeedbackSessionEntities();
        List<FeedbackSessionAttributes> fsaList = new ArrayList<FeedbackSessionAttributes>();
        
        for (FeedbackSession fs : allFs) {
            if (!JDOHelper.isDeleted(fs)) {
                fsaList.add(new FeedbackSessionAttributes(fs));
            }
        }
        return fsaList;
    }
    
    /**
     * Preconditions: <br>
     * * All parameters are non-null.
     * @return An empty list if no non-private sessions are found.
     */
    public List<FeedbackSessionAttributes> getNonPrivateFeedbackSessions() {
        
        List<FeedbackSession> fsList = getNonPrivateFeedbackSessionEntities();
        List<FeedbackSessionAttributes> fsaList = new ArrayList<FeedbackSessionAttributes>();
        
        for (FeedbackSession fs : fsList) {
            if (!JDOHelper.isDeleted(fs)) {
                fsaList.add(new FeedbackSessionAttributes(fs));
            }
        }
        return fsaList;
    }
        
    /**
     * Preconditions: <br>
     * * All parameters are non-null.
     * @return An empty list if no sessions are found for the given course.
     */
    public List<FeedbackSessionAttributes> getFeedbackSessionsForCourse(String courseId) {
        
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseId);
        
        List<FeedbackSession> fsList = getFeedbackSessionEntitiesForCourse(courseId);
        List<FeedbackSessionAttributes> fsaList = new ArrayList<FeedbackSessionAttributes>();
        
        for (FeedbackSession fs : fsList) {
            if (!JDOHelper.isDeleted(fs)) {
                fsaList.add(new FeedbackSessionAttributes(fs));
            }
        }
        return fsaList;
    }
    
    /**
     * Preconditions: <br>
     * * All parameters are non-null.
     * @return An empty list if no sessions are found that have unsent open emails.
     */
    public List<FeedbackSessionAttributes> getFeedbackSessionsWithUnsentOpenEmail() {
                
        List<FeedbackSession> fsList = getFeedbackSessionEntitiesWithUnsentOpenEmail();
        List<FeedbackSessionAttributes> fsaList = new ArrayList<FeedbackSessionAttributes>();
        
        for (FeedbackSession fs : fsList) {
            if (!JDOHelper.isDeleted(fs)) {
                fsaList.add(new FeedbackSessionAttributes(fs));
            }
        }
        return fsaList;
    }
    
    /**
     * Preconditions: <br>
     * * All parameters are non-null.
     * @return An empty list if no sessions are found that have unsent published emails.
     */
    public List<FeedbackSessionAttributes> getFeedbackSessionsWithUnsentPublishedEmail() {
        
        
        List<FeedbackSession> fsList = getFeedbackSessionEntitiesWithUnsentPublishedEmail();
        List<FeedbackSessionAttributes> fsaList = new ArrayList<FeedbackSessionAttributes>();
        
        for (FeedbackSession fs : fsList) {
            if (!JDOHelper.isDeleted(fs)) {
                fsaList.add(new FeedbackSessionAttributes(fs));
            }
        }
        return fsaList;
    }
    
    /**
     * Updates the feedback session identified by {@code newAttributes.feedbackSesionName}
     * and {@code newAttributes.courseId}.
     * For the remaining parameters, the existing value is preserved
     *   if the parameter is null (due to 'keep existing' policy).<br>
     * Preconditions: <br>
     * * {@code newAttributes.feedbackSesionName} and {@code newAttributes.courseId}
     *  are non-null and correspond to an existing feedback session. <br>
     */
    public void updateFeedbackSession(FeedbackSessionAttributes newAttributes)
        throws InvalidParametersException, EntityDoesNotExistException {
        
        Assumption.assertNotNull(
                Const.StatusCodes.DBLEVEL_NULL_INPUT,
                newAttributes);
        
        newAttributes.sanitizeForSaving();
        
        if (!newAttributes.isValid()) {
            throw new InvalidParametersException(newAttributes.getInvalidityInfo());
        }
        
        FeedbackSession fs = (FeedbackSession) getEntity(newAttributes);
        
        if (fs == null) {
            throw new EntityDoesNotExistException(
                    ERROR_UPDATE_NON_EXISTENT + newAttributes.toString());
        }
        fs.setInstructions(newAttributes.getInstructions());
        fs.setStartTime(newAttributes.getStartTime());
        fs.setEndTime(newAttributes.getEndTime());
        fs.setSessionVisibleFromTime(newAttributes.getSessionVisibleFromTime());
        fs.setResultsVisibleFromTime(newAttributes.getResultsVisibleFromTime());
        fs.setTimeZone(newAttributes.getTimeZone());
        fs.setGracePeriod(newAttributes.getGracePeriod());
        fs.setFeedbackSessionType(newAttributes.getFeedbackSessionType());
        fs.setSentOpenEmail(newAttributes.isSentOpenEmail());
        fs.setSentPublishedEmail(newAttributes.isSentPublishedEmail());
        fs.setIsOpeningEmailEnabled(newAttributes.isOpeningEmailEnabled());
        fs.setSendClosingEmail(newAttributes.isClosingEmailEnabled());
        fs.setSendPublishedEmail(newAttributes.isPublishedEmailEnabled());
        
        log.info(newAttributes.getBackupIdentifier());
        getPm().close();
    }
    
    public void addQuestionToSession(
                FeedbackSessionAttributes existingSession, FeedbackQuestionAttributes question) 
            throws EntityDoesNotExistException {
        
        Transaction txn = getPm().currentTransaction();
        try {
            txn.begin();
        
            FeedbackSession fs = (FeedbackSession) getEntity(existingSession);
            
            if (fs == null) {
                throw new EntityDoesNotExistException(
                        ERROR_UPDATE_NON_EXISTENT + existingSession.toString());
            }
            
            fs.getFeedbackQuestions().add(question.toEntity());
            
            getPm().currentTransaction().commit();
        } finally {
            if (txn.isActive()) {
                txn.rollback();
            }
        }
    }

    public void addInstructorRespondant(String email, FeedbackSessionAttributes feedbackSession)
            throws InvalidParametersException, EntityDoesNotExistException {
        
        List<String> emails = new ArrayList<String>();
        emails.add(email);
        addInstructorRespondants(emails, feedbackSession);
    }

    public void addInstructorRespondants(List<String> emails, FeedbackSessionAttributes feedbackSession)
            throws InvalidParametersException, EntityDoesNotExistException {

        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, emails);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, feedbackSession);

        feedbackSession.sanitizeForSaving();

        if (!feedbackSession.isValid()) {
            throw new InvalidParametersException(feedbackSession.getInvalidityInfo());
        }

        FeedbackSession fs = (FeedbackSession) getEntity(feedbackSession);
        if (fs == null) {
            throw new EntityDoesNotExistException(
                    ERROR_UPDATE_NON_EXISTENT + feedbackSession.toString());
        }

        fs.getRespondingInstructorList().addAll(emails);
        
        log.info(feedbackSession.getBackupIdentifier());
        getPm().close();
    }

    public void updateInstructorRespondant(String oldEmail, String newEmail, FeedbackSessionAttributes feedbackSession)
            throws InvalidParametersException, EntityDoesNotExistException {

        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, oldEmail);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, newEmail);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, feedbackSession);

        feedbackSession.sanitizeForSaving();

        if (!feedbackSession.isValid()) {
            throw new InvalidParametersException(feedbackSession.getInvalidityInfo());
        }

        FeedbackSession fs = (FeedbackSession) getEntity(feedbackSession);
        if (fs == null) {
            throw new EntityDoesNotExistException(
                    ERROR_UPDATE_NON_EXISTENT + feedbackSession.toString());
        }

        if (fs.getRespondingInstructorList().contains(oldEmail)) {
            fs.getRespondingInstructorList().remove(oldEmail);
            fs.getRespondingInstructorList().add(newEmail);
        }
       
        log.info(feedbackSession.getBackupIdentifier());
        getPm().close();
    }

    public void clearInstructorRespondants(FeedbackSessionAttributes feedbackSession)
            throws InvalidParametersException, EntityDoesNotExistException {

        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, feedbackSession);

        feedbackSession.sanitizeForSaving();

        if (!feedbackSession.isValid()) {
            throw new InvalidParametersException(feedbackSession.getInvalidityInfo());
        }

        FeedbackSession fs = (FeedbackSession) getEntity(feedbackSession);
        if (fs == null) {
            throw new EntityDoesNotExistException(
                    ERROR_UPDATE_NON_EXISTENT + feedbackSession.toString());
        }

        fs.getRespondingInstructorList().clear();

        log.info(feedbackSession.getBackupIdentifier());
        getPm().close();
    }

    public void addStudentRespondant(String email, FeedbackSessionAttributes feedbackSession)
            throws EntityDoesNotExistException, InvalidParametersException {

        List<String> emails = new ArrayList<String>();
        emails.add(email);
        addStudentRespondants(emails, feedbackSession);
    }

    public void deleteInstructorRespondant(String email, FeedbackSessionAttributes feedbackSession)
            throws InvalidParametersException, EntityDoesNotExistException {

        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, email);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, feedbackSession);

        feedbackSession.sanitizeForSaving();

        if (!feedbackSession.isValid()) {
            throw new InvalidParametersException(feedbackSession.getInvalidityInfo());
        }

        FeedbackSession fs = (FeedbackSession) getEntity(feedbackSession);
        if (fs == null) {
            throw new EntityDoesNotExistException(
                    ERROR_UPDATE_NON_EXISTENT + feedbackSession.toString());
        }

        fs.getRespondingInstructorList().remove(email);

        log.info(feedbackSession.getBackupIdentifier());
        getPm().close();
    }

    public void addStudentRespondants(List<String> emails, FeedbackSessionAttributes feedbackSession)
            throws InvalidParametersException, EntityDoesNotExistException {

        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, emails);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, feedbackSession);

        feedbackSession.sanitizeForSaving();

        if (!feedbackSession.isValid()) {
            throw new InvalidParametersException(feedbackSession.getInvalidityInfo());
        }

        FeedbackSession fs = (FeedbackSession) getEntity(feedbackSession);
        if (fs == null) {
            throw new EntityDoesNotExistException(
                    ERROR_UPDATE_NON_EXISTENT + feedbackSession.toString());
        }

        fs.getRespondingStudentList().addAll(emails);

        log.info(feedbackSession.getBackupIdentifier());
        getPm().close();
    }

    public void updateStudentRespondant(String oldEmail, String newEmail, FeedbackSessionAttributes feedbackSession)
            throws InvalidParametersException, EntityDoesNotExistException {

        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, oldEmail);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, newEmail);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, feedbackSession);

        feedbackSession.sanitizeForSaving();

        if (!feedbackSession.isValid()) {
            throw new InvalidParametersException(feedbackSession.getInvalidityInfo());
        }

        FeedbackSession fs = (FeedbackSession) getEntity(feedbackSession);
        if (fs == null) {
            throw new EntityDoesNotExistException(
                    ERROR_UPDATE_NON_EXISTENT + feedbackSession.toString());
        }

        if (fs.getRespondingStudentList().contains(oldEmail)) {
            fs.getRespondingStudentList().remove(oldEmail);
            fs.getRespondingStudentList().add(newEmail);
        }
        
        log.info(feedbackSession.getBackupIdentifier());
        getPm().close();
    }

    public void clearStudentRespondants(FeedbackSessionAttributes feedbackSession)
            throws InvalidParametersException, EntityDoesNotExistException {

        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, feedbackSession);

        feedbackSession.sanitizeForSaving();

        if (!feedbackSession.isValid()) {
            throw new InvalidParametersException(feedbackSession.getInvalidityInfo());
        }

        FeedbackSession fs = (FeedbackSession) getEntity(feedbackSession);
        if (fs == null) {
            throw new EntityDoesNotExistException(
                    ERROR_UPDATE_NON_EXISTENT + feedbackSession.toString());
        }

        fs.getRespondingStudentList().clear();

        log.info(feedbackSession.getBackupIdentifier());
        getPm().close();
    }

    public void deleteStudentRespondent(String email, FeedbackSessionAttributes feedbackSession)
            throws EntityDoesNotExistException, InvalidParametersException {

        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, email);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, feedbackSession);

        feedbackSession.sanitizeForSaving();

        if (!feedbackSession.isValid()) {
            throw new InvalidParametersException(feedbackSession.getInvalidityInfo());
        }

        FeedbackSession fs = (FeedbackSession) getEntity(feedbackSession);
        if (fs == null) {
            throw new EntityDoesNotExistException(
                    ERROR_UPDATE_NON_EXISTENT + feedbackSession.toString());
        }
        
        fs.getRespondingStudentList().remove(email);

        log.info(feedbackSession.getBackupIdentifier());
        getPm().close();
    }
    
    public void deleteFeedbackSessionsForCourse(String courseId) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseId);
        
        List<String> courseIds = new ArrayList<String>();
        courseIds.add(courseId);
        deleteFeedbackSessionsForCourses(courseIds);
    }
    
    public void deleteFeedbackSessionsForCourses(List<String> courseIds) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseIds);
        
        List<FeedbackSession> feedbackSessionList = getFeedbackSessionEntitiesForCourses(courseIds);
        
        getPm().deletePersistentAll(feedbackSessionList);
        getPm().flush();
    }
    
    @SuppressWarnings("unchecked")
    private List<FeedbackSession> getFeedbackSessionEntitiesForCourses(List<String> courseIds) {
        Query q = getPm().newQuery(FeedbackSession.class);
        q.setFilter(":p.contains(courseId)");
        
        return (List<FeedbackSession>) q.execute(courseIds);
    }
    
    @SuppressWarnings("unchecked")
    private List<FeedbackSession> getAllFeedbackSessionEntities() {
        Query q = getPm().newQuery(FeedbackSession.class);

        return (List<FeedbackSession>) q.execute();
    }
    
    @SuppressWarnings("unchecked")
    private List<FeedbackSession> getNonPrivateFeedbackSessionEntities() {
        Query q = getPm().newQuery(FeedbackSession.class);
        q.declareParameters("Enum private");
        q.setFilter("feedbackSessionType != private");
        
        return (List<FeedbackSession>) q.execute(FeedbackSessionType.PRIVATE);
    }
    
    @SuppressWarnings("unchecked")
    private List<FeedbackSession> getFeedbackSessionEntitiesForCourse(String courseId) {
        Query q = getPm().newQuery(FeedbackSession.class);
        q.declareParameters("String courseIdParam");
        q.setFilter("courseId == courseIdParam");
        
        return (List<FeedbackSession>) q.execute(courseId);
    }
    
    @SuppressWarnings("unchecked")
    private List<FeedbackSession> getFeedbackSessionEntitiesWithUnsentOpenEmail() {
        Query q = getPm().newQuery(FeedbackSession.class);
        q.declareParameters("boolean sentParam, Enum notTypeParam");
        q.setFilter("sentOpenEmail == sentParam && feedbackSessionType != notTypeParam");
        
        return (List<FeedbackSession>) q.execute(false, FeedbackSessionType.PRIVATE);
    }
    
    @SuppressWarnings("unchecked")
    private List<FeedbackSession> getFeedbackSessionEntitiesWithUnsentPublishedEmail() {
        Query q = getPm().newQuery(FeedbackSession.class);
        q.declareParameters("boolean sentParam, Enum notTypeParam");
        q.setFilter("sentPublishedEmail == sentParam && feedbackSessionType != notTypeParam");
        
        return (List<FeedbackSession>) q.execute(false, FeedbackSessionType.PRIVATE);
    }
    
    private FeedbackSession getFeedbackSessionEntity(String feedbackSessionName, String courseId) {
        Key key = KeyFactory.createKey(FeedbackSession.class.getSimpleName(), 
                feedbackSessionName + "%" + courseId);
        try {
            FeedbackSession fs = getPm().getObjectById(FeedbackSession.class, key);
            return fs;
        } catch (JDOObjectNotFoundException e) {
            // return null to be consistent with the other EntitiesDb
            return null;
        }
    }

    @Override
    protected FeedbackSession getEntity(EntityAttributes attributes) {
        FeedbackSessionAttributes feedbackSessionToGet = (FeedbackSessionAttributes) attributes;
        return getFeedbackSessionEntity(feedbackSessionToGet.getFeedbackSessionName(),
                                        feedbackSessionToGet.getCourseId());
    }
}
