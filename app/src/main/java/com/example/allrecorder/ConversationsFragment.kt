package com.example.allrecorder

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.allrecorder.databinding.FragmentConversationsBinding
import kotlinx.coroutines.launch

class ConversationsFragment : Fragment() {

    private var _binding: FragmentConversationsBinding? = null
    private val binding get() = _binding!!

    private lateinit var conversationDao: ConversationDao
    private lateinit var conversationAdapter: ConversationAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        conversationDao = AppDatabase.getDatabase(requireContext()).conversationDao()

        setupRecyclerView()
        observeConversations()
    }

    private fun setupRecyclerView() {
        conversationAdapter = ConversationAdapter { conversation ->
            val intent = Intent(requireContext(), ConversationDetailActivity::class.java).apply {
                putExtra(ConversationDetailActivity.EXTRA_CONVERSATION_ID, conversation.id)
            }
            startActivity(intent)
        }
        binding.rvConversations.apply {
            adapter = conversationAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun observeConversations() {
        lifecycleScope.launch {
            conversationDao.getAllConversations().collect { conversations ->
                conversationAdapter.submitList(conversations)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
