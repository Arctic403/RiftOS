#pragma once

#include <string>
#include <vector>

namespace riftcli {

struct CommandResponse {
    std::string output;
    std::string result_json;
};

CommandResponse execute(const std::vector<std::string>& args, const std::string& cwd);

}  // namespace riftcli
