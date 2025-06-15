#ifndef SIMPLE_GC_H
#define SIMPLE_GC_H

#include <cstddef>

/**
 * @brief Initializes the garbage collector.
 *
 * This function must be called once at the start of the program, before any
 * calls to GC_malloc. It sets up the necessary internal state for the collector,
 * primarily by recording the base of the main thread's stack.
 */
void GC_init();

/**
 * @brief Allocates memory that will be managed by the garbage collector.
 *
 * This function is a replacement for malloc(). It allocates a block of memory
 * of the specified size. If the underlying allocator fails, it triggers a
 * garbage collection cycle and retries the allocation.
 *
 * @param size The number of bytes to allocate.
 * @return A pointer to the beginning of the allocated memory block, or nullptr
 *         if the allocation fails even after a collection cycle.
 */
void* GC_malloc(size_t size);

#endif // SIMPLE_GC_H