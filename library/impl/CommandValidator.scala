package tacit.library

/** Validates commands against ProcessPermission rules.
 *
 *  This object contains the implementation details for command validation,
 *  including the list of file operation commands blocked in strict mode. */
object CommandValidator:

  /** Commands that perform file operations - blocked in strict mode */
  private val fileOperationCommands: Set[String] = Set(
    // Read operations
    "cat", "head", "tail", "less", "more", "tac", "nl",
    // List operations
    "ls", "dir", "find", "locate", "tree", "file", "stat",
    // Write operations
    "touch", "tee", "truncate",
    // Copy/Move operations
    "cp", "mv", "rsync", "scp",
    // Delete operations
    "rm", "rmdir", "unlink", "shred",
    // Directory operations
    "mkdir", "cd", "pwd",
    // Permission operations
    "chmod", "chown", "chgrp",
    // Archive operations
    "tar", "zip", "unzip", "gzip", "gunzip", "bzip2",
    // Link operations
    "ln", "readlink",
    // Disk operations
    "dd", "df", "du",
  )

  /** Validate a command against the ProcessPermission rules.
   *
   *  @throws SecurityException if the command is not allowed or blocked by strict mode */
  def validate(command: String, permission: ProcessPermission): Unit =
    if !permission.allowedCommands.contains(command) then
      throw SecurityException(
        s"Access denied: command '$command' is not in allowed commands ${permission.allowedCommands}"
      )
    if permission.strictMode && fileOperationCommands.contains(command) then
      throw SecurityException(
        s"Strict mode: command '$command' is a file operation. Use requestFileSystem instead."
      )
