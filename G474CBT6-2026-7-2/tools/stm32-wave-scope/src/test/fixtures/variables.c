#include <stdint.h>
#include <stdbool.h>

typedef struct {
    uint16_t count;
    float value;
} Nested;

struct Fixture {
    const uint32_t fixed;
    volatile float editable;
    bool enabled;
    uint8_t small;
    uint16_t half;
    int32_t signed_value;
    uint64_t wide;
    Nested records[2];
    float matrix[2][3];
    struct { int32_t inner; } inline_record;
    const Nested readonly_record;
    uint8_t *pointer;
    unsigned bit : 1;
};

volatile struct Fixture fixture = { .fixed = 42, .editable = 3.0f, .readonly_record = {1, 2.0f} };
