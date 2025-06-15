#include "gc.h"

#include <algorithm>
#include <cstdlib>
#include <iostream>

// The header for each allocated memory block.
struct ObjectHeader {
    bool marked;
    size_t size;
    ObjectHeader* next;
};

// --- Global State for the GC ---

// The head of the linked list of all allocated objects.
static ObjectHeader* heap_start = nullptr;

// The bottom of the stack, captured during initialization.
static void* stack_bottom = nullptr;

// --- Forward Declarations for Internal GC Functions ---

static void gc_collect();
static void gc_mark();
static void gc_mark_from_region(void** start, void** end);
static void gc_mark_object(void* ptr);
static void gc_sweep();
static ObjectHeader* get_header_from_data_ptr(void* ptr);

// --- Public API Implementation ---

void GC_init() {
    // Capture the address of a local variable to get an approximate
    // bottom of the stack. This is a common, though not perfectly
    // portable, technique.
    int dummy;
    stack_bottom = &dummy;
}

void* GC_malloc(size_t size) {
    size_t total_size = sizeof(ObjectHeader) + size;
    ObjectHeader* header = (ObjectHeader*)malloc(total_size);

    // If malloc fails, trigger a garbage collection and try again.
    if (header == nullptr) {
        gc_collect();
        header = (ObjectHeader*)malloc(total_size);

        // If it still fails, we are truly out of memory.
        if (header == nullptr) {
            return nullptr;
        }
    }

    // Initialize the object's header.
    header->marked = false;
    header->size = size;

    // Prepend the new object to the heap list.
    header->next = heap_start;
    heap_start = header;

    // Return a pointer to the user-visible data area, just after the header.
    return (void*)(header + 1);
}

// --- Internal GC Implementation ---

/**
 * @brief Finds the header of an object given a pointer to somewhere in its data.
 * @param ptr A pointer that might point into an allocated object.
 * @return A pointer to the object's header, or nullptr if not found.
 */
static ObjectHeader* get_header_from_data_ptr(void* ptr) {
    // Linearly scan the heap to find which object contains the pointer.
    for (ObjectHeader* p = heap_start; p != nullptr; p = p->next) {
        void* obj_start = (void*)(p + 1);
        void* obj_end = (void*)((char*)obj_start + p->size);
        if (ptr >= obj_start && ptr < obj_end) {
            return p;
        }
    }
    return nullptr;
}

/**
 * @brief Recursively marks an object and all objects it points to as reachable.
 * @param ptr A pointer to a potential object.
 */
static void gc_mark_object(void* ptr) {
    if (ptr == nullptr) {
        return;
    }

    // Find the object's header from the pointer.
    ObjectHeader* header = get_header_from_data_ptr(ptr);

    // If the pointer is not inside any of our objects, or if the object is
    // already marked, we're done.
    if (header == nullptr || header->marked) {
        return;
    }

    // Mark the object as reachable.
    header->marked = true;

    // Recursively scan the object's memory for more pointers.
    // The region to scan is from the start of the user data to its end.
    gc_mark_from_region((void**)(header + 1),
                        (void**)((char*)(header + 1) + header->size));
}

/**
 * @brief Scans a memory region for pointers and marks the objects they point to.
 * @param start The start of the memory region to scan.
 * @param end The end of the memory region to scan.
 */
static void gc_mark_from_region(void** start, void** end) {
    // Iterate through the memory region word by word.
    for (void** p = start; p < end; ++p) {
        // Treat each word as a potential pointer and try to mark it.
        gc_mark_object(*p);
    }
}

/**
 * @brief The main marking phase of the garbage collector.
 *
 * It scans the root set (in this case, just the stack) for pointers
 * into the heap and marks the corresponding objects as reachable.
 */
static void gc_mark() {
    // Get the current top of the stack.
    int dummy;
    void* stack_top = &dummy;

    // The stack grows in different directions on different architectures.
    // We normalize the range so that 'start' is always the lower address.
    void** start = (void**)stack_top;
    void** end = (void**)stack_bottom;
    if (start > end) {
        std::swap(start, end);
    }

    // Scan the entire active stack for pointers.
    gc_mark_from_region(start, end);

    // A more complete GC would also scan global data segments and registers.
}

/**
 * @brief The sweep phase of the garbage collector.
 *
 * It iterates through all allocated objects. If an object is marked, it is
 * kept and unmarked for the next cycle. If it is not marked, it is considered
 * unreachable and is freed.
 */
static void gc_sweep() {
    // Use a pointer-to-pointer to iterate, which simplifies removal.
    ObjectHeader** p = &heap_start;
    while (*p) {
        ObjectHeader* current = *p;
        if (current->marked) {
            // Object is reachable. Unmark it for the next GC cycle and advance.
            current->marked = false;
            p = &current->next;
        } else {
            // Object is unreachable. Unlink it from the list and free it.
            *p = current->next;
            std::cout << "Freeing object of size " << current->size << std::endl;
            free(current);
        }
    }
}

/**
 * @brief Performs a full garbage collection cycle.
 */
static void gc_collect() {
    gc_mark();
    gc_sweep();
}
