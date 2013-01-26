package org.devnull.zuul.service

import org.devnull.error.ConflictingOperationException
import org.devnull.error.ValidationException
import org.devnull.orm.util.JpaPaginationAdapter
import org.devnull.security.service.SecurityService
import org.devnull.util.pagination.Pagination
import org.devnull.zuul.data.config.ZuulDataConstants
import org.devnull.zuul.data.dao.EncryptionKeyDao
import org.devnull.zuul.data.dao.EnvironmentDao
import org.devnull.zuul.data.dao.SettingsDao
import org.devnull.zuul.data.dao.SettingsEntryDao
import org.devnull.zuul.data.dao.SettingsGroupDao
import org.devnull.zuul.data.model.EncryptionKey
import org.devnull.zuul.data.model.Environment
import org.devnull.zuul.data.model.SettingsAudit
import org.devnull.zuul.data.model.SettingsAudit.AuditType
import org.devnull.zuul.data.model.SettingsEntry
import org.devnull.zuul.data.model.SettingsGroup
import org.devnull.zuul.data.specs.SettingsEntryEncryptedWithKey
import org.devnull.zuul.data.specs.SettingsEntrySearch
import org.devnull.zuul.service.error.InvalidOperationException
import org.devnull.zuul.service.security.EncryptionStrategy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Sort
import org.springframework.mail.MailSender
import org.springframework.mail.SimpleMailMessage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.Validator

@Service("zuulService")
@Transactional(readOnly = true)
class ZuulServiceImpl implements ZuulService {

    final def log = LoggerFactory.getLogger(this.class)

    @Autowired
    EncryptionKeyDao encryptionKeyDao

    @Autowired
    SettingsDao settingsDao

    @Autowired
    SettingsGroupDao settingsGroupDao

    @Autowired
    SettingsEntryDao settingsEntryDao

    @Autowired
    EnvironmentDao environmentDao

    @Autowired
    @Qualifier("keyTypeEncryptionStrategy")
    EncryptionStrategy encryptionStrategy

    @Autowired
    MailSender mailSender

    @Autowired
    SimpleMailMessage templateMessage

    @Autowired
    SecurityService securityService

    @Autowired
    AuditService auditService

    @Autowired
    Validator validator

    @Transactional(readOnly = false)
    SettingsGroup createEmptySettingsGroup(String groupName, String environmentName) {
        log.info("Creating empty group for name: {}, environment: {}", groupName, environmentName)
        def env = environmentDao.findOne(environmentName)
        def key = findDefaultKey()
        def group = new SettingsGroup(name: groupName, environment: env, key: key)
        return save(group)
    }

    @Transactional(readOnly = false)
    SettingsGroup createSettingsGroupFromPropertiesFile(String groupName, String environmentName, InputStream inputStream) {
        def group = createEmptySettingsGroup(groupName, environmentName)
        log.info("Appending entries from properties file..")
        def properties = new Properties()
        properties.load(inputStream)
        log.debug("Loading entries: {}", properties)
        properties.each {k, v ->
            group.addToEntries(new SettingsEntry(key: k, value: v))
        }
        return save(group)
    }

    @Transactional(readOnly = false)
    SettingsGroup createSettingsGroupFromCopy(String name, String environmentName, SettingsGroup copy) {
        log.info("Creating copy for name:{}, env:{} from: {}", name, environmentName, copy)
        def env = environmentDao.findOne(environmentName)
        def group = new SettingsGroup(name: name, environment: env, key: copy.key)
        copy.entries.each {
            def entry = new SettingsEntry(key: it.key, value: it.value, encrypted: it.encrypted)
            log.debug("appending entry: {}", entry)
            group.addToEntries(entry)
        }
        return save(group)
    }

    @Transactional(readOnly = false)
    void deleteSettingsGroup(SettingsGroup group) {
        log.info("Deleteing settings group: {} ", group)
        auditService.logAudit(securityService.currentUser, group, SettingsAudit.AuditType.DELETE)
        settingsGroupDao.delete(group.id)
    }

    List<SettingsGroup> findSettingsGroupByName(String name) {
        return settingsDao.findByName(name).groups
    }

    SettingsGroup findSettingsGroupByNameAndEnvironment(String name, String env) {
        log.debug("Finding settings group by name:{}, env:{}", name, env)
        def settings = settingsDao.findByName(name)
        return settingsGroupDao.findBySettingsAndEnvironment(settings, new Environment(name: env))
    }

    List<Environment> listEnvironments() {
        return environmentDao.findAll(new Sort("ordinal", "name")) as List<Environment>
    }

    @Transactional(readOnly = false)
    void deleteEnvironment(String name) {
        environmentDao.delete(name)
    }

    @Transactional(readOnly = false)
    Environment createEnvironment(String name) {
        def environment = new Environment(name: name)
        errorIfInvalid(environment, "environment")
        return environmentDao.save(environment)
    }

    @Transactional(readOnly = false)
    Boolean toggleEnvironmentRestriction(String name) {
        def env = environmentDao.findOne(name)
        env.restricted = !env.restricted
        environmentDao.save(env)
        return env.restricted
    }

    @Transactional(readOnly = false)
    List<Environment> sortEnvironments(List<String> names) {
        log.info("Sorting environments in new order: {}", names)
        def environments = environmentDao.findAll()
        names.eachWithIndex { name, i ->
            environments.find {it.name == name }?.ordinal = i
        }
        return environmentDao.save(environments)
    }

    List<SettingsGroup> listSettingsGroups() {
        return settingsGroupDao.findAll(new Sort("name")) as List<SettingsGroup>
    }

    @Transactional(readOnly = false)
    SettingsEntry encryptSettingsEntryValue(Integer entryId) {
        def entry = settingsEntryDao.findOne(entryId)
        if (entry.encrypted) {
            throw new ConflictingOperationException("Cannot encrypt value that are already encrypted. Entry ID: " + entryId)
        }
        entry.value = encryptionStrategy.encrypt(entry.value, entry.group.key)
        entry.encrypted = true
        return entry
    }

    @Transactional(readOnly = false)
    SettingsEntry decryptSettingsEntryValue(Integer entryId) {
        def entry = settingsEntryDao.findOne(entryId)
        if (!entry.encrypted) {
            throw new ConflictingOperationException("Cannot decrypt value that are already decrypted. Entry ID: " + entryId)
        }
        entry.value = encryptionStrategy.decrypt(entry.value, entry.group.key)
        entry.encrypted = false
        return entry
    }

    SettingsEntry findSettingsEntry(Integer id) {
        return settingsEntryDao.findOne(id)
    }

    @Transactional(readOnly = false)
    void deleteSettingsEntry(SettingsEntry entry) {
        log.info("Deleteing entry: {}", entry)
        auditService.logAudit(securityService.currentUser, entry, SettingsAudit.AuditType.DELETE)
        settingsEntryDao.delete(entry.id)
    }

    @Transactional(readOnly = false)
    SettingsEntry save(SettingsEntry entry) {
        def type = entry.id ? AuditType.MOD : AuditType.ADD
        return save(entry, type)
    }

    @Transactional(readOnly = false)
    SettingsEntry save(SettingsEntry entry, AuditType type) {
        log.info("Saving entry: {}", entry)
        errorIfInvalid(entry, "entry")
        auditService.logAudit(securityService.currentUser, entry, type)
        return settingsEntryDao.save(entry)
    }

    @Transactional(readOnly = false)
    SettingsEntry createEntry(SettingsGroup group, SettingsEntry entry) {
        group.addToEntries(entry)
        if (entry.encrypted) {
            entry.value = encryptionStrategy.encrypt(entry.value, group.key)
            return save(entry, AuditType.ENCRYPT)
        }
        else {
            return save(entry)
        }
    }

    List<SettingsEntry> search(String query, Pagination<SettingsEntry> pagination) {
        log.info("Searching with query: {}, pagination: {}", query, pagination)
        def results = settingsEntryDao.findAll(new SettingsEntrySearch(query), new JpaPaginationAdapter(pagination))
        pagination.results = results.content
        pagination.total = results.totalElements
        return pagination
    }

    @Transactional(readOnly = false)
    SettingsGroup save(SettingsGroup group) {
        log.info("Saving group: {}", group)
        auditService.logAudit(securityService.currentUser, group)
        return settingsGroupDao.save(group)
    }

    void notifyPermissionsRequest(String roleName) {
        def role = securityService.findRoleByName(roleName)
        def requester = securityService.currentUser
        def sysAdmins = securityService.findRoleByName(ZuulDataConstants.ROLE_SYSTEM_ADMIN)
        def emails = sysAdmins.users.collect { it.email } as String[]
        if (emails) {
            def message = new SimpleMailMessage(templateMessage);
            message.to = emails
            message.cc = [requester.email]
            message.subject = "Request for permissions: ${requester.firstName} ${requester.lastName}"
            message.text = "${requester.firstName} ${requester.lastName} has requested access to role: ${role.description}"
            mailSender.send(message)
        }
    }

    @Transactional(readOnly = false)
    void changeKey(SettingsGroup group, EncryptionKey newKey) {
        def existingKey = group.key
        group.entries.each {
            if (it.encrypted) {
                def decrypted = encryptionStrategy.decrypt(it.value, existingKey)
                it.value = encryptionStrategy.encrypt(decrypted, newKey)
            }
        }
        group.key = newKey
        settingsGroupDao.save(group)
    }

    List<EncryptionKey> listEncryptionKeys() {
        return encryptionKeyDao.findAll(new Sort("name")) as List
    }

    @Transactional(readOnly = false)
    EncryptionKey changeDefaultKey(String name) {
        def newKey = encryptionKeyDao.findOne(name)
        if (newKey.defaultKey) {
            return newKey
        }
        def oldKey = findDefaultKey()
        newKey.defaultKey = true
        oldKey.defaultKey = false
        encryptionKeyDao.save([newKey, oldKey])
        return newKey
    }

    EncryptionKey findDefaultKey() {
        def key = encryptionKeyDao.findAll().find { it.defaultKey }
        log.info("Found default encryption key: {}", key)
        //noinspection GroovyAssignabilityCheck
        return key
    }

    EncryptionKey findKeyByName(String name) {
        return encryptionKeyDao.findOne(name)
    }

    @Transactional(readOnly = false)
    EncryptionKey saveKey(EncryptionKey key) {
        errorIfInvalid(key, "key")
        def existingKey = encryptionKeyDao.findOne(key.name)
        if (existingKey && !existingKey.compatibleWith(key)) {
            reEncryptEntriesWithMatchingKey(existingKey, key)
        }
        return encryptionKeyDao.save(key)
    }

    @Transactional(readOnly = false)
    void deleteKey(String name) {
        def key = encryptionKeyDao.findOne(name)
        def existingGroups = settingsGroupDao.findByKey(key)
        if (key.defaultKey) {
            throw new InvalidOperationException("Cannot delete default key")
        }
        if (key.isPgpKey) {
            existingGroups.each {
                if (it.entries.find { it.encrypted }) {
                    throw new InvalidOperationException("Cannot delete PGP keys which are associated to encrypted values.")
                }
            }
        }
        def defaultKey = findDefaultKey()
        existingGroups.each { group ->
            changeKey(group, defaultKey)
        }
        encryptionKeyDao.delete(name)
    }


    protected void reEncryptEntriesWithMatchingKey(EncryptionKey existingKey, EncryptionKey newKey) {
        settingsEntryDao.findAll(new SettingsEntryEncryptedWithKey(existingKey)).each { entry ->
            log.info("re-encrypting entry:{}, oldKey:{}, newKey:{}", entry, existingKey, newKey)
            def decrypted = encryptionStrategy.decrypt(entry.value, existingKey)
            entry.value = encryptionStrategy.encrypt(decrypted, newKey)
            settingsEntryDao.save(entry)
        }
    }


    protected void errorIfInvalid(Object bean, String name) {
        def errors = new BeanPropertyBindingResult(bean, name)
        validator.validate(bean, errors)
        if (errors.hasErrors()) {
            throw new ValidationException(errors)
        }
    }

}
