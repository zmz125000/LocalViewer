#pragma once

#include <algorithm>
#include <cstddef>
#include <system_error>
#include <thread>
#include <vector>

// Reader pages can decode concurrently. Share the cores and avoid starting
// workers for small buffers; the calling thread is included in the budget.
inline unsigned hdr_pixel_jobs(size_t pixels, unsigned max_threads = 0) {
    if (max_threads == 0) {
        const unsigned hw = std::thread::hardware_concurrency();
        max_threads = std::max(1u, (hw == 0 ? 4u : hw) / 2);
    }
    return static_cast<unsigned>(std::min<size_t>(
        std::min(max_threads, 8u), std::max<size_t>(1, pixels / 262144)));
}

template <typename Work>
void hdr_parallel_pixels(size_t pixels, unsigned jobs, Work work) {
    std::vector<std::thread> workers;
    workers.reserve(jobs - 1);
    for (unsigned job = 1; job < jobs; ++job) {
        const size_t begin = pixels * job / jobs;
        const size_t end = pixels * (job + 1) / jobs;
        try {
            workers.emplace_back(work, begin, end, job);
        } catch (const std::system_error&) {
            work(begin, end, job);
        }
    }
    work(0, pixels / jobs, 0);
    for (auto& worker : workers) worker.join();
}
