package com.sionicai.chatbot.common.exception

/**
 * 공통 비즈니스 예외 클래스들
 */
open class BusinessException(
    val errorCode: String,
    override val message: String,
    val httpStatus: Int = 400
) : RuntimeException(message)

class DuplicateResourceException(resource: String, field: String, value: String) :
    BusinessException("DUPLICATE_RESOURCE", "$resource with $field '$value' already exists", 409)

class ResourceNotFoundException(resource: String, id: Any) :
    BusinessException("RESOURCE_NOT_FOUND", "$resource with id '$id' not found", 404)

class UnauthorizedException(message: String = "Invalid credentials") :
    BusinessException("UNAUTHORIZED", message, 401)

class ForbiddenException(message: String = "Access denied") :
    BusinessException("FORBIDDEN", message, 403)
