#include "security_handler.h"

#include <cstdlib>

#ifndef CHIMERA_CRASH_ON_FINDING
#define CHIMERA_CRASH_ON_FINDING 0
#endif

namespace chimera {
namespace security {
namespace handling {

void crashOnFinding() {
#if CHIMERA_CRASH_ON_FINDING
    // 使用原生异常结束进程，便于调试时在 tombstone 中看到触发位置。
    std::abort();
#endif
}

}  // handling 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
