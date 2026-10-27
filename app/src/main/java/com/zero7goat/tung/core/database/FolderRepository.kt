package com.zero7goat.tung.core.database

import kotlinx.coroutines.flow.Flow
import java.util.UUID

class FolderRepository(private val folderDao: FolderDao) {

    fun observeFolders(module: FolderModule): Flow<List<FolderEntity>> =
        folderDao.observeByModule(module.name)

    suspend fun getFolders(module: FolderModule): List<FolderEntity> =
        folderDao.getByModule(module.name)

    suspend fun createFolder(
        module: FolderModule,
        name: String,
        parentId: String? = null
    ): FolderEntity {
        val folder = FolderEntity(
            id = UUID.randomUUID().toString(),
            module = module.name,
            name = name,
            parentId = parentId,
            createdAt = System.currentTimeMillis()
        )
        folderDao.upsert(folder)
        return folder
    }

    suspend fun updateFolder(folder: FolderEntity) {
        folderDao.update(folder)
    }

    suspend fun deleteFolder(folder: FolderEntity) {
        folderDao.delete(folder)
    }
}
