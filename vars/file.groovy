def deleteFile(String path) {
    if (isUnix()) {
        def status = sh script: "rm -f '${path}'", returnStatus: true
        log.info("delete status: ${status}")
        return status
    } else {
        def status = bat script: "del /F /Q \"${path}\"", returnStatus: true
        log.info("delete status: ${status}")
        return status
    }
}
