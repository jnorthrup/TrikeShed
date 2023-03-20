package borg.trikeshed.common.collections

class CircularQueue<T>(capacity: Int, val onEvict: ((T) -> Unit)? = null) {


    // Modify the dequeue method to call the eviction delegate's onEvict method
    fun dequeue(): T {
        if (isEmpty()) throw NoSuchElementException("Queue is empty")
        val element = array[head] as T // Get the element at the head index
        array[head] = null // Clear the slot
        head = (head + 1) % array.size // Increment and wrap around if needed
        size--// Decreasethe size by one
        onEvict?.invoke(element) // Call
        return element// Returnthe removed element
    } // The array that stores the elements

    private val array = arrayOfNulls<Any?>(capacity)

    // The index of the first element
    private var head = 0

    // The index of the next available slot
    private var tail = 0

    // The number of elements in the queue
    private var size = 0

    // Check if the queue is empty
    fun isEmpty(): Boolean {
        return size == 0
    }

    // Check if the queue is full
    fun isFull(): Boolean {
        return size == array.size
    }

    // Add an element to the tail of the queue
    fun enqueue(element: T) {
        if (isFull()) throw IllegalStateException("Queue is full")
        array[tail] = element // Store the element at the tail index
        tail = (tail + 1) % array.size // Increment and wrap around if needed
        size++ // Increase the size by one
    }


    companion object {
        /**
        A factory method that creates a circular queue with a given capacity and initial elements
        e.g. CircularQueue.create(3, 1, 2, 3) { println("Evicted $it") }
         */
        fun <T> create(capacity: Int, vararg elements: T, onEvict1: ((T) -> Unit)? = null): CircularQueue<T> {
            val queue = CircularQueue<T>(
                capacity,
                onEvict = onEvict1
            )// Createa new instance of CircularQueue<T> withthe given capacity
            for (element in elements) {// Loop throughthe given elements
                queue.enqueue(element)// Add each element tothe queue using enqueue() method
            }
            return queue// Returnthe createdqueue instance
        }
    }
}
