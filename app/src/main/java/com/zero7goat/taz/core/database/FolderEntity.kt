package com.zero7goat.taz.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["parentId"]),
        Index(value = ["module"])
    ]
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val module: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
